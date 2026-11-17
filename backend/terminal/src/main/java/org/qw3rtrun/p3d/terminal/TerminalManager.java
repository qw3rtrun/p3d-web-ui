package org.qw3rtrun.p3d.terminal;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;

/**
 * Dials a printer and wraps the socket in a {@link HostTerminal} - the one place in this module
 * that holds a {@code reactor-netty} {@link Connection}, and therefore the only place that can say
 * what closing one means.
 *
 * <p>Closing it is {@link #closeAfterFlush}, not {@code conn::dispose}: handing bytes to
 * {@code reactor-netty}'s outbound is not the same event as those bytes reaching the socket, and
 * the gap between them is wide enough to swallow a whole disconnect. See that method.
 */
@Slf4j
public class TerminalManager {

    private final TcpClient client;

    public TerminalManager(@NonNull ConnectionDetails connectionDetails) {
        this.client = TcpClient.create()
                .host(connectionDetails.host())
                .port(connectionDetails.port())
                .doOnConnect(cfg -> System.out.println("TCPClient doOnConnect " + cfg))
                .doOnConnected(cfg -> System.out.println("TCPClient doOnConnected " + cfg))
                .doOnDisconnected(cfg -> System.out.println("TCPClient doOnDisconnected " + cfg))
                .doOnResolveError((c, exp) -> exp.printStackTrace());
    }

    public Mono<HostTerminal> connect() {
        return client
                .connect()
                .log(this.getClass().getSimpleName() + "#connect")
                .cast(Connection.class)
                .map(conn -> new HostTerminal(
                        () -> conn.inbound().receive()
                                .asString(),
                        // sendByteArray, not sendString: the writer hands over bytes so a binary
                        // packet reaches the wire as itself - sendString encodes UTF-8 and would
                        // turn the AD B5 packet token into four bytes.
                        (p) -> conn.outbound()
                                .sendByteArray(p).then(),
                        // The terminal cancels its two halves; only the thing that opened the
                        // connection can close it, and that is here.
                        closeAfterFlush(conn)
                ));
    }

    /**
     * How a session's socket is shut: a task on the channel's own event loop that flushes first and
     * closes second. Keeps {@link HostTerminal}'s promise that nothing already written is thrown
     * away by the close that follows it.
     *
     * <p><strong>Why a plain {@code conn::dispose} could not keep it.</strong> A write leaves this
     * host in three steps, and only the first is synchronous with the caller:
     *
     * <pre>
     *   CommandProcessor.write0  -&gt; MonoSendMany.onNext: queue.offer(bytes), eventLoop.execute(run)
     *   [event loop] run         -&gt; ctx.write(bytes)  -- into the outbound buffer, NOT flushed,
     *                               because reactor-netty's default send predicate is `b -&gt; false`;
     *                               it then does eventLoop.execute(asyncFlush)
     *   [event loop] asyncFlush  -&gt; ctx.flush()       -- the bytes finally reach the socket
     * </pre>
     *
     * The flush is a <em>second</em> event-loop task, queued only once {@code run} is already
     * executing. A close issued from any other thread in between lands between the two, and
     * {@code AbstractChannel.close} fails everything still unflushed in the outbound buffer. The
     * bytes are discarded in silence - no error reaches the writer, because the writer's
     * {@code onNext} returned long ago.
     *
     * <p>Measured on this repository's own stack (Windows, reactor-netty 1.3.7, 2026-11-17): a
     * block handed to {@code sendByteArray} and a {@code dispose()} on the next line lost the block
     * <strong>39 times in 40</strong>; through the whole {@code TerminalManager -&gt; HostTerminal}
     * stack against a loopback socket, with the scheduler hand-offs {@code PrinterReactor}'s
     * disconnect really makes, <strong>6 times in 20</strong>. That is the CI-only
     * {@code HandshakeIntegrationTest} failure of issue #129, which received {@code []} where it
     * expected a session's two off-switches.
     *
     * <p><strong>Why this ordering holds.</strong> An event loop runs its task queue in order. Any
     * write that happened-before this call has already queued its {@code run}; {@code execute}
     * queues the close behind it, so {@code run} has put the bytes in the outbound buffer before
     * {@code flush()} here pushes them to the socket and {@code dispose()} closes it. Submitting
     * rather than closing inline matters even when the caller <em>is</em> the event loop: an inline
     * {@code close()} from inside an inbound callback would overtake a {@code run} still queued
     * behind it.
     *
     * <p><strong>What it does not promise.</strong> Flushed is "written to the socket", not
     * "received by the printer" and not "acknowledged" - a partial socket write still leaves a
     * remainder that the close discards. A goodbye is three short blocks, far inside one segment.
     *
     * <p><strong>And it may not throw.</strong> {@code HostTerminal.stop()} runs on
     * {@code PrinterReactor}'s own thread, from {@code disconnect()} and from the redial's
     * {@code onConnected}, so an exception escaping it takes the reconnection with it - measured
     * while writing this: a pooled connection whose peer had hung up answered {@code eventLoop()}
     * with {@code IllegalStateException: channel not registered to an event loop} - netty sets that
     * field at registration and never again, so a recycled pooled connection hands back a channel
     * that never had one - every redial died inside {@code onConnected} stopping the previous
     * session, and six {@code :app} integration tests sat at {@code OFFLINE} instead of coming
     * back. {@code Connection.dispose()} never hit it because {@code ChannelOperations.dispose()}
     * short-circuits on a connection that is already disposed; asking for the event loop is what is
     * new. Such a channel has nothing queued on a loop either, so there is nothing to flush ahead
     * of the close and closing here is the whole job.
     */
    static Runnable closeAfterFlush(Connection conn) {
        return () -> {
            var channel = conn.channel();
            try {
                channel.eventLoop().execute(() -> {
                    channel.flush();
                    conn.dispose();
                });
            } catch (RuntimeException noLoopToQueueOn) {
                // IllegalStateException when the channel was never registered to one, and
                // RejectedExecutionException while a live loop is shutting down.
                log.debug("{} has no event loop to close on, so closing here: {}",
                        channel, noLoopToQueueOn.toString());
                disposeQuietly(conn);
            }
        };
    }

    /** The last resort, because a close that throws is a reconnection that never happens. */
    private static void disposeQuietly(Connection conn) {
        try {
            conn.dispose();
        } catch (RuntimeException alreadyBeyondClosing) {
            log.debug("{} could not be closed: {}", conn.channel(),
                    alreadyBeyondClosing.toString());
        }
    }
}
