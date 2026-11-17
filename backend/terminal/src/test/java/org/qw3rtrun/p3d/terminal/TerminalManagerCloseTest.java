package org.qw3rtrun.p3d.terminal;

import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing {@link TerminalManager} decides on its own: what closing a session's socket means.
 *
 * <p>Issue #129. A goodbye handed to {@code reactor-netty}'s outbound is two event-loop tasks short
 * of the wire - {@code run} writes it into the outbound buffer and a separately queued
 * {@code asyncFlush} is what pushes it to the socket - so a {@code Connection.dispose()} issued in
 * between discards it with no error anywhere. The host believes it said goodbye; the printer never
 * heard one.
 *
 * <p>These run over a real {@link TcpClient} and a real loopback {@link ServerSocket}, because the
 * defect is entirely in netty's own write/flush ordering and a {@code Sinks} wire cannot have it.
 *
 * <p>The race is <strong>scheduled, not hoped for</strong>: a task holds the channel's event loop
 * while the write and the close are both queued behind it, so the interleaving that loses the
 * goodbye is the only one either test can produce. Without
 * {@link TerminalManager#closeAfterFlush}, {@link #aBlockWrittenBeforeTheCloseStillReachesTheWire}
 * fails every run. (Blocking inside an event-loop task is exactly what pipeline code may never do
 * and exactly what this needs.)
 */
class TerminalManagerCloseTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** What the disconnect of issue #129 does: write the off-switch, then shut the socket. */
    @Test
    void aBlockWrittenBeforeTheCloseStillReachesTheWire() throws Exception {
        try (var peer = new Peer()) {
            Connection conn = peer.dial();
            Sinks.Many<byte[]> outbound = Sinks.many().unicast().onBackpressureBuffer();
            conn.outbound().sendByteArray(outbound.asFlux()).then().subscribe(v -> {
            }, e -> {
            });

            var released = peer.holdTheEventLoop(conn);
            assertEquals(Sinks.EmitResult.OK, outbound.tryEmitNext("M155 S0\n".getBytes(US_ASCII)));
            TerminalManager.closeAfterFlush(conn).run();
            released.countDown();

            assertEquals("M155 S0\n", peer.readUntilClosed(),
                    "the goodbye was written before the close was asked for, so the close owes it"
                            + " a flush");
        }
    }

    /** And the close still happens - a flush that never came must not leave the socket open. */
    @Test
    void theSocketIsShutEvenWhenThereWasNothingToFlush() throws Exception {
        try (var peer = new Peer()) {
            Connection conn = peer.dial();

            TerminalManager.closeAfterFlush(conn).run();

            assertEquals("", peer.readUntilClosed(), "nothing was written, so nothing arrives");
            assertTrue(conn.channel().closeFuture().await(TIMEOUT.toMillis()),
                    "the socket is shut");
        }
    }

    /**
     * A channel with no event loop to queue the close on closes quietly instead of throwing.
     *
     * <p>{@code HostTerminal.stop()} runs on {@code PrinterReactor}'s own thread - from
     * {@code disconnect()} and from the redial's {@code onConnected}, which stops the dead session
     * before adopting the new one - so an exception escaping it is a reconnection that never
     * happens. Measured: with the first draft of {@link TerminalManager#closeAfterFlush} that
     * reached for {@code eventLoop()} unconditionally, a pooled connection whose peer had hung up
     * answered {@code IllegalStateException: channel not registered to an event loop}, every redial
     * died inside {@code onConnected}, and six {@code :app} integration tests sat at
     * {@code OFFLINE}. {@code Connection.dispose()} never had the problem because
     * {@code ChannelOperations.dispose()} short-circuits on a connection that is already disposed -
     * it is asking for the event loop that is new.
     *
     * <p>Netty sets a channel's event loop at registration and never again, so the state is "never
     * registered" rather than "deregistered" - which is why a {@code deregister()} on a live channel
     * does not produce it and a fresh {@link NioSocketChannel} does, on demand rather than by
     * waiting for a pool to recycle one.
     */
    @Test
    void aChannelWithNoEventLoopIsClosedQuietlyRatherThanThrowing() throws Exception {
        var orphan = new NioSocketChannel();
        try {
            Connection neverRegistered = () -> orphan;
            assertThrows(IllegalStateException.class, orphan::eventLoop,
                    "this is the state a hung-up pooled connection was found in");

            assertDoesNotThrow(() -> TerminalManager.closeAfterFlush(neverRegistered).run(),
                    "stop() may not throw - an escape takes the reconnection with it");
        } finally {
            orphan.unsafe().closeForcibly();
        }
    }

    /** A loopback listener that reads one session to end of stream. */
    private static final class Peer implements AutoCloseable {

        private final ServerSocket server = new ServerSocket(0);

        private final CompletableFuture<String> received = new CompletableFuture<>();

        Peer() throws Exception {
            var reader = new Thread(() -> {
                try (Socket session = server.accept(); InputStream in = session.getInputStream()) {
                    var bytes = new ByteArrayOutputStream();
                    var buffer = new byte[256];
                    for (int read; (read = in.read(buffer)) > 0; ) {
                        bytes.write(buffer, 0, read);
                    }
                    received.complete(bytes.toString(US_ASCII));
                } catch (Exception failed) {
                    received.completeExceptionally(failed);
                }
            }, "issue-129-peer");
            reader.setDaemon(true);
            reader.start();
        }

        Connection dial() {
            return TcpClient.create().host("127.0.0.1").port(server.getLocalPort())
                    .connectNow(TIMEOUT);
        }

        /**
         * Parks the channel's event loop until the returned latch is counted down, so that the
         * write task and the close task are both queued behind this one and their order is the
         * test's to choose rather than the scheduler's.
         */
        CountDownLatch holdTheEventLoop(Connection conn) throws Exception {
            var released = new CountDownLatch(1);
            var holding = new CountDownLatch(1);
            conn.channel().eventLoop().execute(() -> {
                holding.countDown();
                try {
                    released.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(holding.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "the event loop is held");
            return released;
        }

        String readUntilClosed() throws Exception {
            return received.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }
}
