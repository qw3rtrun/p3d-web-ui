package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MConnectionChangedEvent;
import org.qw3rtrun.p3d.terminal.HostTerminal;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.CONNECTING;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.OFFLINE;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.ONLINE;

/**
 * The timing half of plan item B3: retry, backoff, and giving up. No sleeps anywhere: the backoff
 * runs on a {@link VirtualTimeScheduler} the test owns, so "wait out a minute of exponential
 * backoff" is one call - which is also why {@link ConnectionPolicy} and the two schedulers are
 * constructor arguments, not constants. Time is advanced generously rather than exactly, since
 * production jitters its delays on purpose (so several printers dropped at once do not all redial
 * on the same tick), so the sequence is deterministic and the instants are not. What this replaces
 * had none of it: {@code connect()} subscribed once and relied on {@code doOnTerminate}, so a
 * refused or later-dropped connection simply stopped, with nothing ever trying again.
 */
class PrinterReactorRetryTest {

    private final UUID id = UUID.randomUUID();
    private final VirtualTimeScheduler backoff = VirtualTimeScheduler.create();
    private final List<EventMessage> seen = new ArrayList<>();
    private final List<MConnectionChangedEvent> told = new ArrayList<>();

    private Disposable watching;
    private PrinterReactor reactor;

    // TerminalManager is a class, not an interface; this subclass is the cheapest honest way past
    // that - its constructor only builds a TcpClient, so nothing here opens a socket.
    private static final class ScriptedDial extends TerminalManager {
        private final AtomicInteger dialled = new AtomicInteger();
        private final List<Mono<HostTerminal>> answers;

        ScriptedDial(List<Mono<HostTerminal>> answers) {
            super(new ConnectionDetails("localhost", 1));
            this.answers = answers;
        }

        // Mono.defer is the point of the test: the retry resubscribes to the chain connect() was
        // called on once, so a fake that counted calls wrong reported a working retry as broken -
        // real TcpClient.connect() is cold the same way.
        @Override
        public Mono<HostTerminal> connect() {
            return Mono.defer(() -> answers.get(Math.min(dialled.getAndIncrement(), answers.size() - 1)));
        }
    }

    /** A connection that is up until {@code closed} is emitted into, and silent in both directions. */
    private static HostTerminal terminalClosedBy(Sinks.Empty<Void> closed) {
        return new HostTerminal(Flux::never, published -> {
            Flux.from(published).subscribe();
            return closed.asMono();
        });
    }

    private static Mono<HostTerminal> refused() {
        return Mono.error(() -> new ConnectException("Connection refused: no further information"));
    }

    private PrinterReactor reactorOver(ConnectionPolicy policy, ScriptedDial dial) {
        var printer = new PrinterState(id);
        reactor = new PrinterReactor(dial, printer, event -> {
            if (event instanceof MConnectionChangedEvent change) {
                told.add(change);
            }
        }, policy, Schedulers.immediate(), backoff);
        watching = reactor.updates().subscribe(seen::add);
        return reactor;
    }

    @AfterEach
    void letGo() {
        if (watching != null) {
            watching.dispose();
        }
        if (reactor != null) {
            reactor.stop();
        }
        backoff.dispose();
    }

    /** The connection states announced on the stream, in order, after the seeded current state. */
    private List<MachineConnectionState> announced() {
        return seen.stream()
                .map(EventMessage::event)
                .filter(ConnectionStateChanged.class::isInstance)
                .map(event -> ((ConnectionStateChanged) event).to())
                .toList();
    }

    private List<Integer> attempts() {
        return seen.stream()
                .map(EventMessage::event)
                .filter(ConnectionStateChanged.class::isInstance)
                .map(event -> ((ConnectionStateChanged) event).attempt())
                .toList();
    }

    // the attempt numbers matter as much as the states: a farm retrying silently is
    // indistinguishable from one that has given up.
    @Test
    void aRefusedConnectionIsRetriedWithBackoffAndThenGivenUpOn() {
        var dial = new ScriptedDial(List.of(refused()));
        var printer = reactorOver(new ConnectionPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30)), dial);

        printer.handleConnectCmd(new ConnectCmd(true));
        backoff.advanceTimeBy(Duration.ofMinutes(5));

        assertEquals(3, dial.dialled.get(), "the policy asked for three attempts");
        assertEquals(List.of(OFFLINE, CONNECTING, CONNECTING, CONNECTING, OFFLINE), announced());
        assertEquals(List.of(0, 1, 2, 3, 0), attempts());
        assertEquals(OFFLINE, printer.connection().block().state());
    }

    /** Every one of those transitions reaches the roster, which is what persists the state. */
    @Test
    void theRosterIsToldAboutEveryTransition() {
        var dial = new ScriptedDial(List.of(refused()));
        var printer = reactorOver(new ConnectionPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(5)), dial);

        printer.handleConnectCmd(new ConnectCmd(true));
        backoff.advanceTimeBy(Duration.ofMinutes(5));

        assertEquals(List.of(CONNECTING, CONNECTING, OFFLINE),
                told.stream().map(MConnectionChangedEvent::getState).toList());
        assertTrue(told.stream().allMatch(event -> event.getMachineId().equals(id)));
    }

    // same chain as a refused connection, deliberately: everything from dial to end-of-conversation
    // is inside the retry, so "reconnect a dropped machine" needs no second mechanism.
    @Test
    void aDroppedLinkIsDialledAgain() {
        var firstLink = Sinks.<Void>empty();
        var secondLink = Sinks.<Void>empty();
        var dial = new ScriptedDial(List.of(
                Mono.fromSupplier(() -> terminalClosedBy(firstLink)),
                Mono.fromSupplier(() -> terminalClosedBy(secondLink))));
        var printer = reactorOver(new ConnectionPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5)), dial);

        printer.handleConnectCmd(new ConnectCmd(true));
        assertEquals(ONLINE, printer.connection().block().state());

        firstLink.tryEmitEmpty();
        backoff.advanceTimeBy(Duration.ofMinutes(5));

        assertEquals(2, dial.dialled.get());
        assertEquals(ONLINE, printer.connection().block().state());
        assertEquals(List.of(OFFLINE, CONNECTING, ONLINE, CONNECTING, ONLINE), announced());
    }

    // a retry is a subscription holding a timer, so both the intent and the machinery must be
    // cancelled, or the machine comes back a few seconds later as a connection nobody asked for.
    @Test
    void disconnectingDuringTheBackoffStopsIt() {
        var dial = new ScriptedDial(List.of(refused()));
        var printer = reactorOver(new ConnectionPolicy(5, Duration.ofSeconds(10), Duration.ofSeconds(30)), dial);

        printer.handleConnectCmd(new ConnectCmd(true));
        assertEquals(1, dial.dialled.get());

        printer.handleConnectCmd(new ConnectCmd(false));
        backoff.advanceTimeBy(Duration.ofMinutes(5));

        assertEquals(1, dial.dialled.get(), "the backoff fired after the user said stop");
        assertEquals(OFFLINE, printer.connection().block().state());
        assertFalse(announced().isEmpty());
    }

    // two concurrent retry chains against one printer would double the dials and race to set state.
    @Test
    void connectingTwiceDoesNotStartTwoSeries() {
        var dial = new ScriptedDial(List.of(refused()));
        var printer = reactorOver(new ConnectionPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(30)), dial);

        printer.handleConnectCmd(new ConnectCmd(true));
        printer.handleConnectCmd(new ConnectCmd(true));
        backoff.advanceTimeBy(Duration.ofMinutes(5));

        assertEquals(3, dial.dialled.get(), "one series of three, not two");
    }

    // the replay sink is seeded with the current state, so a browser attaching does not wait for the
    // next thing to happen - the page used to infer "online" from any event arriving at all.
    @Test
    void aLateSubscriberIsHandedTheCurrentState() {
        var link = Sinks.<Void>empty();
        var dial = new ScriptedDial(List.of(Mono.fromSupplier(() -> terminalClosedBy(link))));
        var printer = reactorOver(new ConnectionPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5)), dial);
        printer.handleConnectCmd(new ConnectCmd(true));

        var late = new ArrayList<EventMessage>();
        var subscription = printer.updates().subscribe(late::add);

        assertEquals(1, late.size());
        assertEquals(ONLINE, ((ConnectionStateChanged) late.get(0).event()).to());
        subscription.dispose();
    }
}
