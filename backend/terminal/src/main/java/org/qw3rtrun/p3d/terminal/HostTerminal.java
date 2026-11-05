package org.qw3rtrun.p3d.terminal;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.msg.BlockAcknowledged;
import org.qw3rtrun.p3d.api.msg.BlocksAbandoned;
import org.qw3rtrun.p3d.g.code.core.session.GSendWindow;
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.g.marlin.event.SdFileListBegin;
import org.qw3rtrun.p3d.g.marlin.event.SdFileListEnd;
import org.qw3rtrun.p3d.g.marlin.event.SdFileListReader;
import org.qw3rtrun.p3d.g.protocol.AdvancedOkRs;
import org.qw3rtrun.p3d.g.protocol.BusyRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
import org.qw3rtrun.p3d.g.protocol.OkRs;
import org.qw3rtrun.p3d.g.protocol.ResendRs;
import org.qw3rtrun.p3d.g.protocol.SimpleOkRs;
import org.qw3rtrun.p3d.g.protocol.WaitRs;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.Math.min;
import static java.lang.String.format;

/**
 * One terminal session, two coupled pipelines. {@link CommandProcessor} pulls G-code from a source,
 * numbers and frames it through {@code :gcode}'s {@link GSendWindow}, and writes it bounded by the
 * printer's own buffer; {@link ReplyFlux} frames reply lines and decodes each once via
 * {@code MarlinRsDecoder}, and {@link ReplaySubscriber} routes the result back into the send window
 * or onto {@link #messageFlux()}. The coupling is real: an {@code ok} or {@code busy:} arriving on
 * the inbound side is half of outbound flow control.
 *
 * <p>Three threads matter - the producer pushing G-code, the netty inbound loop delivering replies,
 * and whichever of those two first enters a drain loop and so does the writing or emitting for both.
 * Every counter either side shares is a {@link Requested} or guarded by an
 * {@link AtomicInteger}-driven drain loop (see {@link CommandProcessor} and {@link #drainBus()}),
 * never a plain field touched from two threads.
 *
 * <p>{@link #messageFlux()} ends only when the connection does; a firmware error is a value on it
 * (see {@link ReplaySubscriber#onNext}). {@link #stop()} cancels both halves and disposes the
 * connection, because cancelling alone does not close the socket.
 */
@Slf4j
public class HostTerminal {

    private static final Logger TRAFFIC_LOGGER = LoggerFactory.getLogger(HostTerminal.class.getName() + "#traffic");

    private final Supplier<Flux<String>> inbound;
    private final Function<Publisher<String>, Mono<Void>> outbound;

    /**
     * How to close the socket, since cancelling the two halves does not. Needed once {@code cancel()}
     * stopped faking a completion to end the connection (completing the outbound used to be what
     * ended {@code reactor-netty}'s send) - observed on a live disconnect: it reported
     * {@code OFFLINE}, sent its {@code M155 S0}, and left the socket {@code Established} on the
     * printer. The no-arg constructor defaults to doing nothing, which is right for a terminal built
     * over a {@code Sinks} in tests - there is nothing to close.
     */
    private final Runnable disposeConnection;

    public HostTerminal(Supplier<Flux<String>> inbound,
                        Function<Publisher<String>, Mono<Void>> outbound) {
        this(inbound, outbound, () -> {
        });
    }

    public HostTerminal(Supplier<Flux<String>> inbound,
                        Function<Publisher<String>, Mono<Void>> outbound,
                        Runnable disposeConnection) {
        this.inbound = inbound;
        this.outbound = outbound;
        this.disposeConnection = disposeConnection;
    }

    /**
     * How many replies the bus holds for a subscriber that has not attached yet. Was 1
     * ({@code replay().latest()}): the opening exchange of a session is 7 lines
     * ({@code M115}'s {@code FIRMWARE_NAME:}, five {@code Cap:} lines, {@code ok}), and
     * {@link #start} begins reading the socket before a caller can possibly subscribe
     * {@link #messageFlux()} - measured, six replies emitted with nothing attached arrived as one.
     * 256 is {@code publishOn}'s own prefetch and 25x the opening exchange; what matters is that it
     * is not 1, and a subscriber arriving later than that still misses the oldest.
     */
    static final int BACKLOG = 256;

    /**
     * The reply bus. Was {@code Sinks.unsafe()}, which promises its own serialisation - true while
     * every emission came from the netty inbound thread, until {@link BlockAcknowledged} started
     * emitting from inside the send window's drain loop, on whichever thread got there first. The
     * safe sink that replaced it only reports the collision as {@code FAIL_NON_SERIALIZED} rather
     * than corrupting state, and {@link #emit} used to log that and drop the reply - measured, two
     * threads hammering a safe sink: <strong>26,640 of 40,000 emissions refused</strong>. The actual
     * fix is {@link #drainBus()}: it serialises the emitters so the sink never sees two threads.
     */
    private final Sinks.Many<Object> replays = Sinks.many().replay().limit(BACKLOG);

    /** Replies decided on but not yet on the bus, oldest first - what makes {@link #drainBus()}
     * possible: a thread that finds another one already emitting parks here and leaves. */
    private final Queue<Object> outbox = new ConcurrentLinkedQueue<>();

    /** The emission guard: zero when no thread is in {@link #drainBus()}, otherwise the number of
     * calls still to be accounted for. */
    private final AtomicInteger emitting = new AtomicInteger();

    /** Set once a terminal signal has gone out; read only inside {@link #drainBus()}, so a plain
     * field is safe - {@link #emitting}'s hand-off between draining threads carries the
     * happens-before this needs, the same way {@code GSendWindow}'s plain fields are safe. */
    private boolean busEnded;

    /**
     * The end of the bus, parked in the outbox so it cannot overtake a reply queued in front of it -
     * emitting it straight to the sink from the inbound thread could terminate the sink under a
     * still-pending acknowledgement, which the drain loop would then see refused with
     * {@code FAIL_TERMINATED}.
     *
     * @param error what killed the inbound side, or null when it merely ended
     */
    private record BusEnd(Throwable error) {
    }

    private CommandProcessor outSide;
    private ReplaySubscriber inSide;

    /**
     * Opens the session: the socket is read from this call, the writing waits for a subscriber. The
     * asymmetry is deliberate - subscribing the inbound side here, not inside the returned
     * {@code Mono}, means a reply can arrive before the caller subscribes {@link #messageFlux()} (it
     * cannot subscribe any earlier, since this call is what creates both halves); the backlog that
     * covers the gap is described there.
     */
    public Mono<Void> start(Publisher<String> gcodes) {
        this.inSide = new ReplaySubscriber();
        this.outSide = new CommandProcessor(gcodes);
        inbound.get()
                .as(ReplyFlux::new)
                .subscribe(inSide);
        return outbound.apply(outSide);
    }

    /** Ends the session: both halves cancelled, and the socket actually closed - the third step is
     * the one that is easy to leave out; see {@link #disposeConnection}. */
    public void stop() {
        outSide.cancel();
        inSide.handleCancel();
        disposeConnection.run();
    }

    /**
     * The replies worth telling a subscriber about, already decoded - typed {@code GRs} values, not
     * text, decoded once by {@code :gcode}'s {@code MarlinRsDecoder} and propagated as-is. (A second
     * model used to sit on this boundary, {@code ReplyEvents} turning each {@code GRs} into an
     * {@code api.msg} record - nullable {@code BigDecimal} into zero-filled {@code double} - and it
     * is gone: this farm drives Marlin, so what Marlin said is what a subscriber gets.)
     *
     * <p>What drives the protocol is consumed rather than published: a bare/advanced {@code ok},
     * {@code wait}, {@code Resend}. A line no decoder claims is dropped with a {@code #traffic}
     * debug line. {@code M20}'s multi-line answer arrives as one {@link SdFileList}, assembled by
     * {@link ReplyFlux#listing}.
     *
     * <p>Two values on this flux are not replies at all, both facts about the send window a
     * subscriber cannot otherwise see: {@link BlockAcknowledged} for an {@code ok} that released a
     * block (the only honest progress signal there is), and {@link BlocksAbandoned} for a
     * {@code Resend:} the window could not answer (see
     * {@link CommandProcessor#resynchronise}) - emitted only when a block really was dropped.
     *
     * <p>This flux terminates when the connection does, and at no other time - a printer fault is an
     * {@code ErrorRs} value like any other reply (see {@link ReplaySubscriber#onNext}), not an
     * {@code onError} here.
     *
     * <p><strong>It does not matter when this is subscribed, or which thread decided a reply.</strong>
     * {@link #start} begins reading the socket before any caller can possibly subscribe, so the bus
     * holds a backlog of {@link #BACKLOG} replies for a subscriber not yet attached, and every
     * emission goes through one drain loop rather than straight at the sink - every reply reaches the
     * first subscriber, in order, whenever it attaches, and a second subscriber gets the same backlog
     * then the live stream.
     */
    public Flux<Object> messageFlux() {
        return replays.asFlux();
    }

    public void onOk(OkRs<?> ok) {
        outSide.handleBackpressure(ok);
        publishPayload(ok);
    }

    /**
     * An {@code ok} is an acknowledgement, and sometimes also a reply - {@code M105}/{@code M109}/
     * {@code M190} answer on the {@code ok} itself (e.g. {@code ok T:45.26 /45.00 B:25.00 /0.00}), so
     * dropping every {@code ok} as pure window credit used to answer an on-demand {@code M105} into
     * the void. Only {@link SimpleOkRs} (nothing to decode) and {@link AdvancedOkRs} (window credit
     * only, e.g. {@code ok N2 P15 B3}) are held back; everything else goes out whole, with the
     * {@code ok} surviving as a type rather than a string prefix - {@code OkTemperatureRs} vs.
     * {@code BareTemperatureRs} is exactly that distinction.
     */
    private void publishPayload(OkRs<?> ok) {
        if (ok instanceof SimpleOkRs || ok instanceof AdvancedOkRs) {
            return;
        }
        emit(ok);
    }

    /**
     * The machine cannot take commands, so nothing in flight will be acknowledged. Matches
     * {@link BusyRs} rather than a concrete class so both spellings fire: the RepRap bare
     * {@code busy: processing} ({@code SimpleBusyRs}) and Marlin's echoed
     * {@code echo:busy: processing} ({@code BusyNotice}) - the parser this replaced claimed only the
     * bare form, so on a real Marlin the send window was never told the printer had stalled.
     */
    void onBusy(BusyRs<?> busy) {
        outSide.clearInFlight();
        emit(busy);
    }

    /**
     * {@code Resend: n} (spec 8.5 step 5): the firmware rejected a numbered block and discards
     * everything after it until it sees {@code n} again. {@link CommandProcessor#rewind} replays
     * block {@code n} and everything sent after it, byte for byte. Not published - like an advanced
     * {@code ok}, it is protocol traffic the transport owns; a subscriber sees the {@code Error:}
     * that precedes it instead.
     */
    void onResend(int lineNumber) {
        outSide.rewind(lineNumber);
    }

    void onWait() {
        // TODO Unsupported yet
    }

    /**
     * Puts a reply on the bus - parked first, so two threads never emit at once. The two that reach
     * here are the netty inbound loop (a decoded reply) and whichever thread's send-window drain
     * loop released a {@link BlockAcknowledged}; calling {@code tryEmitNext} directly from either and
     * logging a {@code FAIL_NON_SERIALIZED} collision, as before, drops the reply after the fact
     * instead of preventing the collision.
     */
    private void emit(Object reply) {
        outbox.add(reply);
        drainBus();
    }

    /**
     * Ends the bus - reserved for the transport dying. A fault the <em>printer</em> reported is a
     * value through {@link #emit} instead; routing it here used to terminate the sink (and every
     * subscriber of {@link #messageFlux()}) over a checksum the printer itself recovered from on the
     * next line.
     */
    private void endBus(Throwable error) {
        outbox.add(new BusEnd(error));
        drainBus();
    }

    /**
     * Publishes what is parked, one thread at a time - atomics plus a drain loop, the same shape as
     * {@link CommandProcessor#processRequest()} and {@link PublisherQueue}. The first caller in runs
     * the loop; one that arrives while it is running leaves its reply in the outbox and returns, and
     * the thread inside goes round again to pick it up - nothing blocks or busy-waits, unlike
     * {@code emitNext(reply, EmitFailureHandler.busyLooping(...))}. Because this is the only path to
     * the sink, {@code FAIL_NON_SERIALIZED} is now unreachable there.
     */
    private void drainBus() {
        if (emitting.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        for (; ; ) {
            for (Object parked; (parked = outbox.poll()) != null; ) {
                publish(parked);
            }
            missed = emitting.addAndGet(-missed);
            if (missed == 0) {
                return;
            }
        }
    }

    /** One parked item onto the sink. Drain loop only, so {@link #busEnded} is plain. */
    private void publish(Object parked) {
        if (parked instanceof BusEnd end) {
            if (busEnded) {
                return;
            }
            busEnded = true;
            Sinks.EmitResult result = end.error() == null
                    ? replays.tryEmitComplete()
                    : replays.tryEmitError(end.error());
            if (result.isFailure()) {
                log.warn("IN: the bus did not terminate ({}): {}", result, end.error());
            }
            return;
        }
        if (busEnded) {
            // Nothing follows a terminal signal, ever. A late acknowledgement from the send window
            // is the normal way to get here and is not worth a warning.
            log.debug("IN: the bus has ended, so this reply goes nowhere: {}", parked);
            return;
        }
        Sinks.EmitResult result = replays.tryEmitNext(parked);
        if (result.isFailure()) {
            log.warn("IN: reply dropped ({}): {}", result, parked);
        }
    }

    /**
     * Frames reply lines off the wire and decodes each once through {@code MarlinRsDecoder}. Built
     * once per session, in {@link #start}, on the netty inbound loop, which is the only thread that
     * ever touches it.
     */
    public static class ReplyFlux extends FluxOperator<String, GRs<?>> {

        private final Flux<GRs<?>> flux;

        /**
         * {@code M20}'s listing, being assembled - the one stateful thing on this side, and it has
         * to live here: a listing's entries ({@code Begin file list}, entries, {@code End file list})
         * are deliberately not claimable replies ({@code TEST.GCO 47} is too common a
         * {@code <word> <number>} shape for a decoder to own), so they reach {@link #decode} as lines
         * no decoder wants and have nowhere else to be collected.
         */
        private final SdFileListReader listing = new SdFileListReader();

        public ReplyFlux(Flux<String> source) {
            super(source);
            this.flux = source
                    .flatMap(s -> Flux.fromStream(s.chars().mapToObj(i -> (char) i).map(Object::toString)))
                    .bufferUntil("\n"::equals)
                    .map(l -> String.join("", l).trim())
                    .doOnNext((in) -> TRAFFIC_LOGGER.debug("<- {}", in))
                    .handle(this::decode);
        }

        /**
         * One received line as the reply it says it is, or nothing when no decoder claims it -
         * {@code handle} rather than {@code map} because {@code MarlinRsDecoder} answers null for a
         * line outside what it reads (boot banner, {@code M503} dump, calibration report), which a
         * {@code Flux} may not carry; it goes to the traffic logger instead.
         *
         * <p>A file listing is assembled rather than forwarded: both markers are swallowed as
         * scaffolding, an entry is swallowed because it was never a reply, and a listing something
         * interrupted produces <strong>nothing</strong> rather than a short list reported as the
         * card's contents - see {@code SdFileListRs}.
         */
        private void decode(String line, SynchronousSink<GRs<?>> sink) {
            SdFileList files = listing.read(line);
            if (files != null) {
                sink.next(files);
                return;
            }
            GRs<?> reply = MarlinRsDecoder.INSTANCE.decode(line);
            if (reply == null) {
                // Not "unread" while a list is open: an entry is a line this host understands
                // perfectly well and has just put into the listing being assembled.
                TRAFFIC_LOGGER.debug(listing.isReading() ? "<- (in a listing) {}" : "<- (unread) {}", line);
                return;
            }
            if (reply instanceof SdFileListBegin || reply instanceof SdFileListEnd) {
                return;
            }
            sink.next(reply);
        }

        @Override
        public void subscribe(CoreSubscriber<? super GRs<?>> actual) {
            flux.subscribe(actual);
        }
    }

    /**
     * The outbound half: pulls G-code text, numbers and checksums it, writes it, and replays it when
     * the printer asks.
     *
     * <p>The drain loop owns the send window. {@link GSendWindow} is portable Kotlin, not
     * thread-safe, and touched from different threads - a command pulled on whichever thread the
     * source produces on, an {@code ok}/{@code Resend:} off the netty inbound loop - so none of them
     * touches it directly: {@link #onNext} parks the payload, {@link #handleBackpressure} and
     * {@link #rewind} park an event, and all three call {@link #processRequest()}, whose {@code wip}
     * guard admits one thread at a time to apply the parked work and call {@code send}/
     * {@code acknowledge}/{@code resendFrom}. That also puts every write in one place, which a replay
     * needs: its blocks must reach the wire in original order and before anything pulled since, which
     * a write from the inbound thread could not promise.
     *
     * <p>What a caller may hand in: one bare command per item - no {@code N}, no {@code *} checksum,
     * no terminator, no open comment - framed verbatim by {@code GEncoder.frame}. The line counter is
     * this layer's to set, not a caller's to send: an {@code M110} sent as text would move the
     * firmware's counter without moving this window's and desynchronise the session. This layer does
     * set it - the first block of every session is {@code M110 N0}, and the same block repairs a
     * sequence that has drifted past the window (see {@link #opening}, {@link #resynchronise}); before
     * that, a host reconnecting to a printer that had not been power-cycled could not send anything.
     */
    public class CommandProcessor implements Processor<String, String>, Subscription {

        /**
         * One thing the printer said about the send window, parked for the drain loop. A record
         * rather than the sentinel integer this replaced ({@code ACK = -1} in a {@code Queue<Integer>}
         * else a resend request), which had nowhere to put the line number an advanced {@code ok}
         * carries without it being read as a retransmit request.
         *
         * @param kind   what the printer said
         * @param number the line it named, or null for a bare {@code ok}, which names none
         */
        private record WindowEvent(Kind kind, Integer number) {

            enum Kind {ACKNOWLEDGED, RESEND}

            static WindowEvent acknowledged(Integer number) {
                return new WindowEvent(Kind.ACKNOWLEDGED, number);
            }

            static WindowEvent resend(int number) {
                return new WindowEvent(Kind.RESEND, number);
            }
        }

        Publisher<String> gcode;
        Subscription subscription;
        Subscriber<? super String> subscriber;
        private final Requested subscriptionRequested = new Requested();
        private final Requested requestedFromGCode = new Requested();
        /**
         * Commands written to the printer that it has not acknowledged yet - a {@link Requested}
         * because it needs the same discipline demand does (non-negative, saturating, incremented and
         * decremented from different threads), not because it is demand. It was a {@code volatile int}
         * before: {@code volatile} publishes a write but does not make a read-modify-write atomic.
         */
        private final Requested sentWithoutAck = new Requested();
        /**
         * The printer's own buffer counts, as the last advanced {@code ok} reported them - kept as
         * the {@link AdvancedOkRs} itself rather than a transport-local copy, after a copy here once
         * read the {@code N} field at a position Marlin does not write it, so the window never
         * widened. Starts at one block, the window a printer reporting no counts gets. {@code volatile}
         * because it is written from the inbound thread and read by {@link #canProcess()} while
         * draining - a reference write is atomic, so a snapshot is all this needs.
         */
        private volatile AdvancedOkRs backpressure = new AdvancedOkRs(1, 1, null);
        /** The drain guard: zero when no thread is in {@link #processRequest()}, otherwise the
         * number of calls still to be accounted for. */
        private final AtomicInteger wip = new AtomicInteger();

        /**
         * Spec 8.5's host side, from {@code :gcode}: numbers, frames and keeps what it framed. Not a
         * second copy of {@link #sentWithoutAck} - the two counts are meant to diverge: this field is
         * how much buffer this host believes it occupies, zeroed by a {@code busy:}; the window's
         * {@code getInFlight()} is what this host must still be able to <em>replay</em>, which a
         * {@code busy:} must not touch, since those are the exact bytes a later {@code Resend:} asks for.
         */
        private final GSendWindow window = new GSendWindow();

        /**
         * Payloads pulled from the source and not framed yet, oldest first. A deque, not a queue, so
         * a payload could go back where it came from if the window ever refused it - unreachable
         * today since {@link #canProcess()} reserves the slot before the pull, but the alternative is
         * dropping a command in silence, which this module exists to prevent.
         */
        private final Deque<String> pending = new ConcurrentLinkedDeque<>();

        /** How many of those there are, so {@link #canProcess()} can hold room for them. */
        private final Requested awaitingFrame = new Requested();

        /** Window events parked in the order the printer stated them - an acknowledgement, or a
         * {@code Resend:}'s line number - one queue because the order is the protocol. */
        private final Queue<WindowEvent> windowEvents = new ConcurrentLinkedQueue<>();

        /** Blocks a {@code Resend:} asked for, framed already, waiting for downstream demand.
         * Touched only from the drain loop, so a plain {@link ArrayDeque} is enough. */
        private final Deque<String> replaying = new ArrayDeque<>();

        /**
         * The block that opens the session - {@code M110 N0}, framed - until it has been written.
         * Spec 7.2 names it as how a host starts a session: the window numbers from {@code N1}, and a
         * firmware still powered from a previous session remembers the line it last accepted, so
         * without this every block of a reconnection is rejected with
         * {@code Error:Line Number is not Last Line Number+1} and an unanswerable {@code Resend:} -
         * dead in the outbound direction for good. Measured against the simulator: a plain reconnect
         * reached exactly that state, meaning only the first session after a power-on had ever worked.
         * {@code M110} is exempt from the continuity check for this reason.
         *
         * <p>Also what {@link #resynchronise} parks when a session drifts past repair, so both routes
         * into a fresh sequence are one piece of code. Written through the drain loop like any other
         * block - it costs a unit of demand and a buffer slot until its {@code ok} returns - rather
         * than poked straight at the socket, which would be an unasked {@code onNext} the window never
         * counted.
         */
        private String opening;

        /**
         * Set by a {@code Resend:} and cleared by the {@code ok} that follows it. Marlin answers a
         * rejected block with {@code Error:}, {@code Resend: n}, then {@code ok} - that {@code ok}
         * acknowledges no block, and letting it reach {@link GSendWindow#acknowledge()} would discard
         * the block the rewind just made outstanding again.
         */
        private final AtomicBoolean rewinding = new AtomicBoolean();

        /**
         * Set once the subscriber has been told the stream is over, by either route - needed because
         * the drain loop can be started by a reply, and an {@code ok}/{@code Resend:} arriving after
         * {@link HostTerminal#stop()} would otherwise write what was parked in {@link #pending} behind
         * the terminal signal. Also makes {@link #cancel()} idempotent. A flag, not serialisation: a
         * cancel landing between the check and the write still races, since cancellation bypasses the
         * drain loop - closing that needs the loop itself to own the terminal signal, a shape change
         * rather than a field change.
         */
        private final AtomicBoolean terminated = new AtomicBoolean();

        /**
         * Set when the G-code source is exhausted; the drain loop turns it into a completion.
         * Separate from {@link #terminated} because the source running out is not the end of the
         * stream until what it already handed over has been written - see
         * {@link #completeIfDrained()}.
         */
        private final AtomicBoolean sourceComplete = new AtomicBoolean();

        private final Supplier<String> report = () -> format("(s=%s, r=%s u=%s w=%s n=%d/%d b=%s)",
                subscriptionRequested, requestedFromGCode, awaitingFrame, sentWithoutAck,
                window.getInFlight(), window.getCapacity(), backpressure);

        public CommandProcessor(Publisher<String> gCode) {
            this.gcode = gCode;
            this.opening = window.reset(0);
        }

        /**
         * One {@code ok}: a free slot in the printer's buffer, and usually one block released from
         * the send window - not the {@code ok} closing a resend handshake, which frees nothing this
         * host is still counting, since {@link #rewind} already recorded that the firmware discarded
         * the rejected block and everything after it unacknowledged. Marlin's advanced form,
         * {@code ok N5 P15 B3}, carries its own line number through to the window rather than being
         * counted: counting cannot self-correct - a lost or extra {@code ok} shifts the window's
         * correspondence permanently, and an unknown number is answered with an empty list, not a
         * complaint.
         */
        public void handleBackpressure(OkRs<?> ok) {
            Integer acknowledged = null;
            if (ok instanceof AdvancedOkRs advanced) {
                backpressure = advanced;
                acknowledged = advanced.getLineNumber();
            }
            if (rewinding.compareAndSet(true, false)) {
                log.info("OUT: ok closing a resend - credit only, it acknowledges no block: {} {}",
                        ok.encode(), report.get());
            } else {
                sentWithoutAck.dec();
                windowEvents.add(WindowEvent.acknowledged(acknowledged));
                log.info("OUT: backpressure applied: {} {}", ok.encode(), report.get());
            }
            outSide.processRequest();
        }

        /** Nothing is in flight that the printer will acknowledge, so its buffer is all of it.
         * {@link #window} is deliberately left alone - a stalled machine has not accepted those
         * blocks, and a {@code Resend:} for them is the likeliest thing next. */
        void clearInFlight() {
            sentWithoutAck.reset();
        }

        /**
         * How many more commands may be pulled: the subscriber's demand, bounded by the printer's
         * buffer and the send window, whichever is tightest - both bounds reduced by commands already
         * spoken for ({@code requestedFromGCode}, {@code awaitingFrame}), since each will occupy a
         * slot the moment it arrives. The window term is what makes a refused {@code send} impossible:
         * a block stays in the window until acknowledged, so the host may never hold more outstanding
         * than it can replay. Zero while a replay is waiting for demand (nothing newer may jump ahead
         * of blocks the firmware is sitting on) or while the session's {@code M110} is owed (no agreed
         * sequence yet for a block to take a place in - see {@link #opening}).
         */
        long canProcess() {
            if (!replaying.isEmpty() || opening != null) {
                return 0;
            }
            long spokenFor = requestedFromGCode.get() + awaitingFrame.get();
            return min(subscriptionRequested.get(),
                    min(backpressure.getBlockQueue() - sentWithoutAck.get() - spokenFor,
                            window.getCapacity() - window.getInFlight() - spokenFor));
        }

        /**
         * Moves as much of the subscriber's demand upstream as the printer currently has room for.
         * The only place the three counters move against each other, so it has to be one step: atomic
         * counters make each one safe alone, but not {@code canProcess() -> inc/dec -> request(n)} as
         * a sequence, and two threads - a {@code request(n)} and an {@code ok} off the netty loop -
         * could each read the same room and claim it. {@code wip} serialises the section instead of a
         * lock: the first caller in runs the loop, a later one leaves a mark and returns, and the
         * thread inside goes round again - which also makes re-entrancy safe, since {@link #onNext}
         * calls this from inside {@code subscription.request}.
         */
        void processRequest() {
            if (wip.getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            for (; ; ) {
                if (!terminated.get()) {
                    applyWindowEvents();
                    write();
                    completeIfDrained();
                    long canProcess = canProcess();
                    if (canProcess > 0 && subscription != null) {
                        requestedFromGCode.inc(canProcess);
                        subscriptionRequested.dec(canProcess);
                        log.info("Process request {} of {} but {}", canProcess, subscriptionRequested, report.get());
                        subscription.request(canProcess);
                    }
                }
                missed = wip.addAndGet(-missed);
                if (missed == 0) {
                    return;
                }
            }
        }

        /**
         * Applies the acknowledgements and resend requests the inbound side parked, in order - drain
         * loop only, and the order is the protocol's own: an {@code ok} ahead of a {@code Resend:}
         * released a block the firmware really took, and applying them reversed would rewind over it.
         *
         * <p>The acknowledgement signal has to be emitted from here: the window is the only thing
         * that knows which block an {@code ok} answered (a bare {@code ok} carries no number, only
         * the window's FIFO order), and the window may only be touched from this loop - which is why
         * {@link HostTerminal#replays} could no longer be an unsafe sink.
         */
        private void applyWindowEvents() {
            for (WindowEvent event; (event = windowEvents.poll()) != null; ) {
                if (event.kind() == WindowEvent.Kind.RESEND) {
                    rewind0(event.number());
                    continue;
                }

                // Spec 8.5's in-order acknowledgement: a named `ok` releases that block and
                // everything still held in front of it (lost acknowledgements); a bare one has only
                // the window's FIFO order to go on.
                int before = window.getInFlight();
                Integer released = event.number() == null
                        ? window.acknowledge()
                        : window.acknowledge(event.number());

                if (released == null) {
                    log.debug("OUT: ok acknowledging no block this window holds {} {}",
                            event.number() == null ? "(unnumbered)" : "N" + event.number(), report.get());
                    continue;
                }
                int releasedCount = before - window.getInFlight();
                if (releasedCount > 1) {
                    log.info("OUT: ok N{} released {} blocks - {} acknowledgement(s) were lost {}",
                            released, releasedCount, releasedCount - 1, report.get());
                }
                emit(new BlockAcknowledged(released, window.getInFlight()));
            }
        }

        /**
         * Spec 8.5 step 5: put the blocks from {@code from} onwards back in hand, byte for byte. The
         * printer's buffer count is rebuilt, not adjusted, since a {@code Resend:} means the firmware
         * discarded the rejected block and everything after it unacknowledged - what it will occupy
         * next is the replay and nothing else. An empty replay means the session has drifted further
         * than a replay can repair (the firmware asked for a block this host no longer holds, which
         * cannot happen in a well-behaved session); see {@link #resynchronise}.
         */
        private void rewind0(int from) {
            List<String> replay = window.resendFrom(from);
            if (replay.isEmpty()) {
                resynchronise(from);
                return;
            }
            sentWithoutAck.reset();
            sentWithoutAck.inc(replay.size());
            log.warn("OUT: Resend: {} - replaying {} block(s) {}", from, replay.size(), report.get());
            replaying.addAll(replay);
        }

        /**
         * Starts the numbering again: reached when the firmware asks for a block the window no
         * longer holds, so there is no sequence left both ends agree on and every later block would
         * be rejected on the same grounds. The abandoned blocks are dropped, loudly - they cannot be
         * replayed under a new number without re-framing, and the window holds the <em>bytes</em> it
         * emitted rather than the commands behind them (spec 8.3 makes the checksum a property of the
         * bytes); re-reading them to renumber would put a second parser back in the transport, and
         * would be unsafe besides, since a relative move replayed under a new number is a move made
         * twice.
         *
         * <p>The layer above is told: these are lines of a job that will never reach the machine, and
         * a print that lost them used to run to end of file and report {@code COMPLETED}. A
         * {@link BlocksAbandoned} goes on the bus instead - {@code JobFeeder} fails the job on it,
         * since it cannot be retried from a checkpoint that records what was sent and not what was
         * accepted. The count is read before the reset and the event emitted after, through
         * {@link HostTerminal#emit} on this drain loop (same reason as {@link BlockAcknowledged}: this
         * is the thread allowed to read the window). Nothing abandoned, nothing announced - a
         * resynchronisation that dropped nothing cost nothing, and without the gate the firmware's
         * pile-up of identical unanswerable {@code Resend:}s would announce one lost print three times.
         */
        private void resynchronise(int from) {
            int abandoned = window.getInFlight();
            opening = window.reset(0);
            sentWithoutAck.reset();
            log.warn("OUT: Resend: {} - not held by the send window, so the session is out of sync;"
                            + " resynchronising with {} and abandoning {} block(s) {}",
                    from, opening, abandoned, report.get());
            if (abandoned > 0) {
                emit(new BlocksAbandoned(from, abandoned));
            }
        }

        /**
         * Writes what is owed: the session's {@code M110} first (nothing may go out ahead of it, or
         * the firmware rejects the gap), then replay (its blocks carry numbers the firmware is
         * waiting for), then what was pulled since. A replayed block spends a fresh unit of the
         * subscriber's demand - the unit it spent when first written is gone, and no {@code onNext}
         * goes out unasked even for a retransmission - which is why each of the three stages returns
         * rather than falling through once demand runs out. See {@link #opening}.
         */
        private void write() {
            if (opening != null) {
                if (subscriptionRequested.get() <= 0) {
                    return;
                }
                subscriptionRequested.dec();
                sentWithoutAck.inc();
                String reset = opening;
                opening = null;
                write0(reset);
            }
            while (!replaying.isEmpty() && subscriptionRequested.get() > 0) {
                subscriptionRequested.dec();
                write0(replaying.pollFirst());
            }
            if (!replaying.isEmpty()) {
                return;
            }
            for (String payload; (payload = pending.pollFirst()) != null; ) {
                String framed = window.send(payload);
                if (framed == null) {
                    pending.addFirst(payload);
                    log.warn("OUT: the send window refused a command already pulled {}", report.get());
                    return;
                }
                awaitingFrame.dec();
                sentWithoutAck.inc();
                write0(framed);
            }
        }

        private void write0(String block) {
            if (terminated.get()) {
                return;
            }
            TRAFFIC_LOGGER.debug("-> {}", block);
            subscriber.onNext(block + "\n");
        }

        /**
         * Parks a {@code Resend: n} for the drain loop, and marks the {@code ok} behind it.
         *
         * <p>Called from the inbound thread, which may not touch the window and may not write.
         */
        void rewind(int lineNumber) {
            rewinding.set(true);
            windowEvents.add(WindowEvent.resend(lineNumber));
            processRequest();
        }

        @Override
        public void subscribe(Subscriber<? super String> s) {
            gcode.subscribe(this);
            subscriber = s;
            subscriber.onSubscribe(this);
        }

        @Override
        public void request(long n) {
            subscriptionRequested.inc(n);
            log.info("Subscription Request {} {}", n, report.get());
            processRequest();
        }

        /**
         * Idempotent, and the stream stays over: a second call and a late reply both do nothing. No
         * longer answers a cancel with {@code onComplete} (rule 2.4 forbids a terminal signal on a
         * cancel path) - that used to re-enter {@code StepVerifier}'s state machine on every
         * expectation mismatch and spin the build at 100% CPU instead of reporting it. Closing the
         * connection is {@code PrinterReactor.disconnect}'s job via {@link HostTerminal#disposeConnection};
         * this class only ever had two lambdas, never the {@code Connection}, so faking a completion
         * was the only way it could end a session before.
         */
        @Override
        public void cancel() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            subscriptionRequested.reset();
            log.info("Subscription Cancel {}", report.get());
            if (subscription != null) {
                subscription.cancel();
            }
        }

        @Override
        public void onSubscribe(Subscription s) {
            log.info("OUT onSubscription {}", s);
            this.subscription = s;
        }

        /**
         * One command off the source: parked, not written. Runs on whatever thread the source
         * produces on ({@link PublisherQueue} pushes from {@code addPublisher} as readily as from
         * {@code request}), so it may neither frame nor write - both go to the drain loop. Counted
         * into {@link #awaitingFrame} before {@link #requestedFromGCode} is decremented, so the room
         * it is about to occupy is never momentarily unaccounted for in {@link #canProcess()}.
         */
        @Override
        public void onNext(String gCode) {
            awaitingFrame.inc();
            requestedFromGCode.dec();
            pending.addLast(gCode);
            log.info("GCode Next {} {}", gCode, report.get());
            processRequest();
        }

        @Override
        public void onError(Throwable t) {
            log.warn("GCode Error {}", report.get(), t);
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            subscriber.onError(t);
        }

        /**
         * The source is exhausted, but not necessarily written out yet - completing directly here
         * would drop commands, since a payload sits in {@link #pending} until there is demand to
         * write it and {@link #write0} refuses once {@code terminated} is set. So this only records
         * that the source is done; the drain loop decides when that becomes a completion, in
         * {@link #completeIfDrained()}.
         */
        @Override
        public void onComplete() {
            log.info("GCode Complete {}", report.get());
            sourceComplete.set(true);
            processRequest();
        }

        /**
         * Completes the subscriber once the source is done <em>and</em> everything owed - the
         * session's {@code M110}, a requested replay, payloads pulled but not yet framed - is on the
         * wire. Drain loop only, so "anything left to write" is asked on the one thread that could be
         * writing. It does not wait for acknowledgements: whether the firmware has taken a block is a
         * different question, answered block by block by {@code BlockAcknowledged}.
         */
        private void completeIfDrained() {
            if (!sourceComplete.get() || terminated.get()) {
                return;
            }
            if (opening != null || !replaying.isEmpty() || !pending.isEmpty()) {
                return;
            }
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            log.info("OUT: the source is exhausted and everything it gave us is written {}", report.get());
            subscriber.onComplete();
        }
    }

    public class ReplaySubscriber implements Subscriber<GRs<?>> {

        private Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
            this.subscription = s;
            subscription.request(Long.MAX_VALUE);
        }

        public void handleCancel() {
            if (subscription != null) {
                subscription.cancel();
            }
            subscription = null;
        }

        /**
         * Routes one decoded reply: half into the send window, half onto the bus. Matched on the
         * family interfaces, not concrete classes, so a reply type added to {@code :gcode} under
         * {@link OkRs} (bare, advanced or temperature-carrying - all acknowledgements) or
         * {@link BusyRs} (bare {@code busy:} and Marlin's {@code echo:busy:}) arrives already routed;
         * the arms are a sequence rather than a partition, since {@code OkTemperatureRs} is an
         * {@link OkRs} first.
         *
         * <p>{@code ErrorRs} has no arm, deliberately - it falls to the default and is published as a
         * value, since on a Marlin an error is routine traffic the printer recovers from by itself:
         *
         * <pre>
         * &gt;&gt; N3 M105*1
         * &lt;&lt; Error:checksum mismatch, Last Line: 2
         * &lt;&lt; Resend: 3
         * &lt;&lt; ok
         * </pre>
         *
         * <p>Routing it to {@code tryEmitError} instead used to terminate {@link HostTerminal#messageFlux()}
         * for the rest of the session over one mistyped checksum, while the printer answered normally.
         * A terminal signal there now means only that the inbound side is gone - {@link #onError} and
         * {@link #onComplete} below. Which errors are fatal is not decided here: {@code :gcode} keeps
         * the machine's own words as text, and that judgement belongs to the host.
         */
        @Override
        public void onNext(GRs<?> reply) {
            log.info("IN: onNext({})", reply);
            switch (reply) {
                case OkRs<?> ok -> onOk(ok);
                case BusyRs<?> busy -> onBusy(busy);
                case WaitRs _ -> onWait();
                case ResendRs resend -> onResend(resend.getLineNumber());
                default -> emit(reply);
            }
        }

        @Override
        public void onError(Throwable t) {
            log.error("IN: onError()", t);
            endBus(t);
        }

        @Override
        public void onComplete() {
            log.warn("IN: onComplete()");
            endBus(null);
        }
    }

}
