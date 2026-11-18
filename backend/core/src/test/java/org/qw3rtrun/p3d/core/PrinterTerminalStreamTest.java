package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.TerminalTraffic;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console stream - issue #135. Two claims, and the second is the one with teeth.
 *
 * <p><strong>It replays, where every other sink in this module deliberately does not</strong>
 * (#85's rule), because a console's content <em>is</em> its scrollback and a page opened mid-session
 * would otherwise be blank until the operator typed something. {@code TerminalTraffic.at} and the
 * separation below are what keep that from being history presented as news.
 *
 * <p><strong>And nothing on it reaches the monitor stream or the aggregate.</strong> Routing a
 * traffic line to {@code PrinterState.onEvent} is the obvious-looking thing and is a live bug: that
 * method's {@code default} arm calls {@code heard()}, so a {@code SENT} line would make the host's
 * own writing count as the machine having spoken.
 *
 * <p>{@code Schedulers.immediate()} for both schedulers, so an emission and its delivery happen on
 * the test thread in the order the code decides them; {@link PrinterReactor#onTraffic} is driven
 * directly because the production caller is {@code HostTerminal}'s drain loop and reaching it would
 * need a socket.
 */
class PrinterTerminalStreamTest {

    private final UUID id = UUID.randomUUID();

    private PrinterState printer;
    private PrinterReactor reactor;
    private Disposable watching;

    @BeforeEach
    void build() {
        printer = new PrinterState(id);
        reactor = new PrinterReactor(new TerminalManager(new ConnectionDetails("localhost", 1)),
                printer, event -> {
        }, ConnectionPolicy.standard(), Schedulers.immediate(), Schedulers.parallel());
    }

    @AfterEach
    void letGo() {
        if (watching != null) {
            watching.dispose();
        }
        reactor.stop();
    }

    private static TerminalTraffic sent(String raw) {
        return new TerminalTraffic(TerminalTraffic.Direction.SENT, raw, null, 1763467200000L);
    }

    private static TerminalTraffic received(String raw, String decodedAs) {
        return new TerminalTraffic(TerminalTraffic.Direction.RECEIVED, raw, decodedAs,
                1763467200123L);
    }

    @Test
    void aLineTheTransportSawReachesTheConsole() {
        reactor.onTraffic(received("ok P15 B3", "AdvancedOkRs"));

        StepVerifier.create(reactor.traffic())
                .assertNext(message -> {
                    assertEquals(id, message.id(), "the console says which machine it is about");
                    assertEquals("TerminalTraffic", message.type(),
                            "the type string the browser switches on");
                    var line = assertInstanceOf(TerminalTraffic.class, message.event());
                    assertEquals("ok P15 B3", line.raw());
                    assertEquals("AdvancedOkRs", line.decodedAs());
                })
                .thenCancel()
                .verify();
    }

    /**
     * The separation, from the monitor side. Both halves matter: the console gets the line and the
     * monitor stream gets nothing but the snapshot it opens with.
     */
    @Test
    void aConsoleLineDoesNotReachTheMonitorStream() {
        var monitor = new CopyOnWriteArrayList<EventMessage>();
        watching = reactor.updates().subscribe(monitor::add);
        var openedWith = monitor.size();

        reactor.onTraffic(sent("N12 M105*31"));
        reactor.onTraffic(received("Sim Startup!", null));

        assertEquals(openedWith, monitor.size(),
                "a wire line reached the monitor stream, which fans out to the farm-wide dashboard"
                        + " and to every watcher that decides something from a machine's events: "
                        + monitor.stream().map(EventMessage::type).toList());
    }

    /**
     * And from the aggregate's side, which is where the damage would be. {@code onEvent}'s
     * {@code default} arm calls {@code heard()}; an {@code OFFLINE} machine fed its own outbound
     * block would be a connection moved by the host talking to itself.
     */
    @Test
    void aConsoleLineDoesNotMoveTheConnection() {
        var before = printer.getConnection().state();

        reactor.onTraffic(sent("N12 M105*31"));

        assertEquals(MachineConnectionState.OFFLINE, before, "the machine was never dialled");
        assertEquals(before, printer.getConnection().state(),
                "a line this host wrote is not news from the machine");
    }

    /** The whole point of the replay: a console opened after the conversation still shows it. */
    @Test
    void aLateSubscriberIsHandedTheScrollback() {
        reactor.onTraffic(sent("N1 M110 N0*125"));
        reactor.onTraffic(received("ok", "SimpleOkRs"));
        reactor.onTraffic(sent("N2 M115*39"));

        StepVerifier.create(reactor.traffic().map(PrinterTerminalStreamTest::raw))
                .expectNext("N1 M110 N0*125", "ok", "N2 M115*39")
                .thenCancel()
                .verify();
    }

    /**
     * Bounded at 256, and the 256 kept are the <em>latest</em> - asserted by which line arrives
     * first rather than by counting, since a count assertion passes against a sink that kept
     * everything.
     */
    @Test
    void theScrollbackIsBoundedToTheLatestLines() {
        for (var i = 0; i < 300; i++) {
            reactor.onTraffic(received("line " + i, null));
        }

        StepVerifier.create(reactor.traffic().map(PrinterTerminalStreamTest::raw))
                .assertNext(first -> assertEquals("line 44", first,
                        "300 lines in, a 256-deep console should start at line 44 - it started at "
                                + first))
                .thenCancel()
                .verify();
    }

    /**
     * A browser holding the console of a deleted printer must be told, not left waiting - the same
     * guarantee {@code PrinterReactorManagerTest.aReleasedReactorCompletesItsUpdateStream} pins for
     * the monitor stream, and the reason {@code StreamEnd} completes both sinks rather than one.
     */
    @Test
    void stoppingEndsTheConsoleStream() {
        reactor.onTraffic(received("ok", "SimpleOkRs"));

        StepVerifier.create(reactor.traffic())
                .expectNextCount(1)
                .then(reactor::stop)
                .verifyComplete();
    }

    /** And a line emitted after the stop goes nowhere rather than failing the sink. */
    @Test
    void aLineArrivingAfterTheStopIsDropped() {
        reactor.stop();

        reactor.onTraffic(received("ok", "SimpleOkRs"));

        StepVerifier.create(reactor.traffic())
                .verifyComplete();
    }

    /** A console subscriber is one {@code awaitSubscribersGone} waits for, like a monitor one. */
    @Test
    void aConsoleSubscriberIsOneTheShutdownWaitsFor() {
        watching = reactor.traffic().subscribe();

        reactor.stop();

        assertTrue(reactor.awaitSubscribersGone(Duration.ofSeconds(5)),
                "the console subscriber's stream should have completed");
    }

    private static String raw(EventMessage message) {
        return ((TerminalTraffic) message.event()).raw();
    }
}
