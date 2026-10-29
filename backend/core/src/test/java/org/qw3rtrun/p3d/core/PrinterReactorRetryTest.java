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
 * The timing half of plan item B3: retry, backoff, and giving up.
 *
 * <p><strong>No sleeps anywhere.</strong> The backoff is timed on a {@link VirtualTimeScheduler}
 * the test owns, so "wait out a minute of exponential backoff" is one call and the whole file runs
 * in milliseconds. That is also why {@link ConnectionPolicy} and the two schedulers are
 * constructor arguments rather than constants: a retry policy that cannot be shortened is a retry
 * policy that cannot be tested.
 *
 * <p>Time is advanced generously rather than by the exact backoff, because the production policy
 * jitters its delays on purpose - several printers dropped by one network blip must not all redial
 * on the same tick - so the <em>sequence</em> is deterministic and the instants are not.
 *
 * <p>What this replaces had none of it. {@code connect()} subscribed once and relied on
 * {@code doOnTerminate} to notice the end; a machine that refused the connection, or dropped it an
 * hour later, simply stopped being connected and nothing ever tried again.
 */
class PrinterReactorRetryTest {

    private final UUID id = UUID.randomUUID();
    private final VirtualTimeScheduler backoff = VirtualTimeScheduler.create();
    private final List<EventMessage> seen = new ArrayList<>();
    private final List<MConnectionChangedEvent> told = new ArrayList<>();

    private Disposable watching;
    private PrinterReactor reactor;

    /**
     * A terminal manager that answers from a script instead of dialling.
     *
     * <p>{@code TerminalManager} is a class and not an interface, and this subclass is the cheapest
     * honest way past that: its constructor only builds a {@code TcpClient}, so nothing here opens
     * a socket or needs a printer to exist.
     */
    private static final class ScriptedDial extends TerminalManager {
        private final AtomicInteger dialled = new AtomicInteger();
        private final List<Mono<HostTerminal>> answers;

        ScriptedDial(List<Mono<HostTerminal>> answers) {
            super(new ConnectionDetails("localhost", 1));
            this.answers = answers;
        }

        /**
         * <p>{@code Mono.defer}, and the reason is the point of the test. The retry resubscribes to
         * the chain {@code connect()} was called on once, so a fake that counted calls would have
         * counted one dial however many times it redialled - which is exactly what the first
         * version of this file did, and it reported a working retry as a broken one. The real
         * {@code TcpClient.connect()} is cold in the same way: the dial happens per subscription.
         */
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

    /**
     * A machine that refuses is dialled the policy's number of times and then let alone.
     *
     * <p>The attempt numbers are the assertion that matters as much as the states: a farm that
     * retries silently is indistinguishable from one that has given up, which is what the plan item
     * means by "says so on the event stream".
     */
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

    /**
     * A link that drops after it was established is redialled, not merely forgotten.
     *
     * <p>The same chain as a refused connection, deliberately: everything from the dial to the end
     * of the conversation is inside the retry, so "reconnect a machine that dropped" needed no
     * second mechanism with its own backoff and its own bugs.
     */
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

    /**
     * Disconnecting mid-backoff stops the series instead of being recorded over it.
     *
     * <p>A retry is a subscription holding a timer, so the intent and the machinery both have to be
     * cancelled. Without the second half a machine told to disconnect would come back a few seconds
     * later, and the only trace would be a connection nobody asked for.
     */
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

    /**
     * An impatient second click does not start a second series.
     *
     * <p>Two concurrent retry chains against one printer would double the dials and race to set the
     * state, and nothing in the aggregate would have been able to tell.
     */
    @Test
    void connectingTwiceDoesNotStartTwoSeries() {
        var dial = new ScriptedDial(List.of(refused()));
        var printer = reactorOver(new ConnectionPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(30)), dial);

        printer.handleConnectCmd(new ConnectCmd(true));
        printer.handleConnectCmd(new ConnectCmd(true));
        backoff.advanceTimeBy(Duration.ofMinutes(5));

        assertEquals(3, dial.dialled.get(), "one series of three, not two");
    }

    /**
     * A subscriber that arrives late is told where the machine is straight away.
     *
     * <p>The health signal in its stream form: the replay sink is seeded with the current state, so
     * a browser attaching to a printer does not have to wait for the next thing to happen to it.
     * The page used to infer "online" from the fact that <em>any</em> event had arrived.
     */
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
