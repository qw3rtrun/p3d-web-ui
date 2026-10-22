package org.qw3rtrun.p3d.terminal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.msg.GEvent;
import org.qw3rtrun.p3d.g.code.core.session.GSendWindow;
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
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

@Slf4j
@RequiredArgsConstructor
public class HostTerminal {

    private static final Logger TRAFFIC_LOGGER = LoggerFactory.getLogger(HostTerminal.class.getName() + "#traffic");

    private final Supplier<Flux<String>> inbound;
    private final Function<Publisher<String>, Mono<Void>> outbound;

    private Sinks.Many<GEvent> replays = Sinks.unsafe().many().replay().latest();

    private CommandProcessor outSide;
    private ReplaySubscriber inSide;

    public Mono<Void> start(Publisher<String> gcodes) {
        this.inSide = new ReplaySubscriber();
        this.outSide = new CommandProcessor(gcodes);
        inbound.get()
                .as(ReplyFlux::new)
                .subscribe(inSide);
        return outbound.apply(outSide);
    }

    public void stop() {
        outSide.cancel();
        inSide.handleCancel();
    }

    /**
     * The replies worth telling a subscriber about, already decoded.
     *
     * <p>Typed values and not text, because the line was decoded once on the way in - by
     * {@code :gcode}'s {@code MarlinRsDecoder}, the one reply model in this repository. The copy
     * that used to live in {@code terminal.msg.Replay} is gone: it had drifted from the real wire
     * format in three places, and {@code PrinterReactor} was decoding the text a second time
     * anyway, so every line was being read by two parsers that disagreed.
     *
     * <p>Not every received line reaches here. The ones that drive the protocol and say nothing a
     * subscriber did not already learn from the send window are consumed: a bare and an advanced
     * {@code ok}, {@code wait}, and {@code Resend}. Everything else is published - except a line no
     * decoder claimed, which is dropped with a {@code #traffic} debug line, because {@code GRs} has
     * no value for "unread" and the raw text has no consumer.
     *
     * <p><strong>This flux terminates when the connection does, and at no other time.</strong> An
     * {@code onError} here means the inbound side died - the socket, or the decoder - and not that
     * the printer reported a fault. A printer fault is an {@code ErrorRs} value like any other
     * reply; see {@link ReplaySubscriber#onNext}.
     */
    public Flux<GEvent> messageFlux() {
        return replays.asFlux();
    }

    public void onOk(OkRs<?> ok) {
        outSide.handleBackpressure(ok);
        publishPayload(ok);
    }

    /**
     * An {@code ok} is an acknowledgement, and it is sometimes also a reply.
     *
     * <p>{@code M105}, {@code M109} and {@code M190} all answer on the {@code ok} itself -
     * {@code ok T:45.26 /45.00 B:25.00 /0.00} - so an {@code ok} that is taken only as a window
     * credit and then dropped takes the answer with it. That is what happened before: every
     * temperature the host ever saw came from an {@code M155} auto-report, which arrives on a line
     * of its own, and an {@code M105} asked on demand was answered into the void.
     *
     * <p>Two kinds of {@code ok} are held back. {@link SimpleOkRs} carries nothing to decode, and
     * {@link AdvancedOkRs} - {@code ok N2 P15 B3} - carries only the window credit this method was
     * already given. Publishing either would put a reply on the bus that says nothing a subscriber
     * did not already know.
     *
     * <p>Everything else answering to {@link OkRs} goes out whole, and the {@code ok} survives as a
     * <em>type</em> rather than as a prefix on a string: {@code OkTemperatureRs} is the answer to
     * an {@code M105} and {@code BareTemperatureRs} is an unsolicited auto-report, and those two
     * classes exist to keep exactly that distinction.
     */
    private void publishPayload(OkRs<?> ok) {
        if (ok instanceof SimpleOkRs || ok instanceof AdvancedOkRs) {
            return;
        }
        emit(ReplyEvents.of(ok));
    }

    /**
     * The machine cannot take commands right now, so nothing is in flight that it will acknowledge.
     *
     * <p>Both spellings arrive here. The RepRap protocol's bare {@code busy: processing} is
     * {@code SimpleBusyRs}; Marlin writes the identical notice through its echo channel as
     * {@code echo:busy: processing}, which is {@code BusyNotice}, and both answer to
     * {@link BusyRs}. Matching the interface is the whole point of it being one: the parser this
     * replaced claimed the bare form only, so on a real Marlin this never fired and the send window
     * was never told the printer had stalled.
     */
    void onBusy(BusyRs<?> busy) {
        outSide.clearInFlight();
        emit(ReplyEvents.of(busy));
    }

    /**
     * The printer wants a line back, and this host has it.
     *
     * <p>{@code Resend: n} is spec 8.5 step 5: the firmware rejected a numbered block - a bad
     * checksum, or a gap in the sequence - and discards everything that follows until it sees
     * {@code n} again. Answering it means replaying the exact bytes of block {@code n} and of every
     * block sent after it, which is what {@link CommandProcessor#rewind} does through
     * {@code :gcode}'s {@code GSendWindow}.
     *
     * <p>Not published. A {@code Resend:} is protocol traffic the transport owns and answers, and
     * it says nothing a subscriber needs - exactly like an advanced {@code ok}. What a subscriber
     * does see is the {@code Error:} that precedes it, which carries the firmware's own words.
     */
    void onResend(int lineNumber) {
        outSide.rewind(lineNumber);
    }

    void onWait() {
        // TODO Unsupported yet
    }

    /**
     * Puts a reply on the bus, and says so when it did not go.
     *
     * <p>{@code tryEmitNext} answers with an {@link Sinks.EmitResult} and fails silently when it is
     * not {@code OK} - a dropped reply here is a temperature reading that never reaches the UI, so
     * it is worth a line in the log rather than nothing at all.
     */
    private void emit(GEvent reply) {
        Sinks.EmitResult result = replays.tryEmitNext(reply);
        if (result.isFailure()) {
            log.warn("IN: reply dropped ({}): {}", result, reply);
        }
    }

    /**
     * Kills the bus, which is reserved for the transport dying.
     *
     * <p>One caller: {@link ReplaySubscriber#onError}. A fault the <em>printer</em> reported is a
     * value and goes through {@link #emit} - putting it here terminated the sink and every
     * subscriber of {@link #messageFlux()} with it, over a checksum the printer itself recovered
     * from on the next line.
     */
    private void emitError(Throwable t) {
        Sinks.EmitResult result = replays.tryEmitError(t);
        if (result.isFailure()) {
            log.warn("IN: error dropped ({})", result, t);
        }
    }

    public static class ReplyFlux extends FluxOperator<String, GRs<?>> {

        private final Flux<GRs<?>> flux;

        public ReplyFlux(Flux<String> source) {
            super(source);
            this.flux = source
                    .flatMap(s -> Flux.fromStream(s.chars().mapToObj(i -> (char) i).map(Object::toString)))
                    .bufferUntil("\n"::equals)
                    .map(l -> String.join("", l).trim())
                    .doOnNext((in) -> TRAFFIC_LOGGER.debug("<- {}", in))
                    .handle(ReplyFlux::decode);
        }

        /**
         * One received line as the reply it says it is, or nothing when no decoder claims it.
         *
         * <p>{@code handle} and not {@code map}, because {@code MarlinRsDecoder} answers with null
         * for a line outside the set it reads - the boot banner, an {@code M503} settings dump, a
         * calibration report - and a {@code Flux} may not carry a null. Null is the normal case and
         * not an error: the line goes to the traffic logger and is dropped, which is what the layer
         * above did with it before.
         */
        private static void decode(String line, SynchronousSink<GRs<?>> sink) {
            GRs<?> reply = MarlinRsDecoder.INSTANCE.decode(line);
            if (reply == null) {
                TRAFFIC_LOGGER.debug("<- (unread) {}", line);
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
     * The outbound half: it pulls G-code text, numbers and checksums it, writes it, and replays it
     * when the printer asks.
     *
     * <p><strong>The drain loop owns the send window.</strong> {@link GSendWindow} is portable
     * Kotlin with no atomics in it and is not thread-safe, and the three things that touch it come
     * from different threads - a command is pulled on whichever thread the source produces on, an
     * {@code ok} and a {@code Resend:} arrive on the netty inbound loop. So none of them touches it
     * directly. {@link #onNext} parks the payload, {@link #handleBackpressure} and {@link #rewind}
     * park an event, and all three then call {@link #processRequest()}, whose {@code wip} guard
     * admits one thread at a time; that thread applies the parked work and is the only one that
     * ever calls {@code send}, {@code acknowledge} or {@code resendFrom}. The guard is an
     * {@link AtomicInteger}, so the hand-off also carries the happens-before the window's plain
     * fields need.
     *
     * <p>That also puts every write in one place, which the replay needs: the blocks a
     * {@code Resend:} asks for have to reach the wire in their original order and <em>before</em>
     * anything pulled since, and a write issued from the inbound thread could not promise either.
     *
     * <p><strong>What a caller may hand in.</strong> One bare command per item - no {@code N}
     * field, no {@code *} checksum, no line terminator, and no comment left open: this is what
     * {@code GEncoder.frame} is given verbatim, and it documents the rest. A pre-numbered block is
     * framed <em>in front of</em> its own {@code N}, and an {@code M110} sent as text moves the
     * firmware's counter without moving this window's, which desynchronises the session until the
     * window is {@code reset} - so the counter is this layer's to set and not a caller's to send.
     */
    public class CommandProcessor implements Processor<String, String>, Subscription {

        /** A parked {@code ok}: the oldest outstanding block is done. Line numbers are never negative. */
        private static final int ACK = -1;

        Publisher<String> gcode;
        Subscription subscription;
        Subscriber<? super String> subscriber;
        private final Requested subscriptionRequested = new Requested();
        private final Requested requestedFromGCode = new Requested();
        /**
         * Commands written to the printer that it has not acknowledged yet.
         *
         * <p>A {@link Requested} and not a counter of its own. It is not demand, but it is the same
         * discipline - a non-negative saturating count, incremented on one thread and decremented
         * on another - and the rule this module works to is that the accounting is <em>one</em>
         * mechanism rather than half of one. It was a {@code volatile int} with {@code += 1} in
         * {@link #onNext}, {@code --} in {@link #handleBackpressure} and {@code = 0} in
         * {@link HostTerminal#onBusy}: {@code volatile} publishes a write, it does not make a
         * read-modify-write atomic, and the first two of those run on different threads.
         */
        private final Requested sentWithoutAck = new Requested();
        /**
         * The printer's own buffer counts, as the last advanced {@code ok} reported them.
         *
         * <p>An {@link AdvancedOkRs} and not a transport-local copy of the same three numbers. The
         * record that used to hold them here read the {@code N} field in a position Marlin does not
         * write it in, so on a numbered line it matched nothing and the window never widened.
         *
         * <p>Starts at one block, which is the window a printer that reports no counts at all gets:
         * one command out, and nothing more until it is acknowledged.
         *
         * <p>{@code volatile} because it is written from the inbound thread and read by
         * {@link #canProcess()} on whichever thread is draining. A reference write is atomic on its
         * own, so a snapshot is all the window needs and no lock is involved.
         */
        private volatile AdvancedOkRs backpressure = new AdvancedOkRs(1, 1, null);
        /**
         * The drain guard: zero when no thread is in {@link #processRequest()}, and otherwise the
         * number of calls that still have to be accounted for.
         */
        private final AtomicInteger wip = new AtomicInteger();

        /**
         * Spec 8.5's host side, from {@code :gcode}: it numbers, frames and keeps what it framed.
         *
         * <p>Not rebuilt here, and not a second copy of {@link #sentWithoutAck}. The two counts
         * answer different questions and are meant to diverge: {@code sentWithoutAck} is how much
         * of the printer's command buffer this host believes it is occupying, which a {@code busy:}
         * zeroes because nothing in flight will be acknowledged while the machine is stalled;
         * {@link GSendWindow#getInFlight()} is what this host must still be able to <em>replay</em>,
         * which a {@code busy:} must not touch, because those are exactly the bytes a later
         * {@code Resend:} asks for.
         */
        private final GSendWindow window = new GSendWindow();

        /**
         * Payloads pulled from the source and not framed yet, oldest first.
         *
         * <p>A deque rather than a queue so a payload can go back where it came from if the window
         * ever refuses it. {@link #canProcess()} reserves the slot before the command is pulled, so
         * that is unreachable - but the alternative on the day it is not would be dropping a
         * command in silence, which is the failure this module exists to prevent.
         */
        private final Deque<String> pending = new ConcurrentLinkedDeque<>();

        /** How many of those there are, so {@link #canProcess()} can hold room for them. */
        private final Requested awaitingFrame = new Requested();

        /**
         * Window events parked in the order the printer stated them: {@link #ACK}, or the line
         * number of a {@code Resend:}. One queue and not two, because the order is the protocol.
         */
        private final Queue<Integer> windowEvents = new ConcurrentLinkedQueue<>();

        /**
         * Blocks a {@code Resend:} asked for, framed already, waiting for downstream demand.
         *
         * <p>Touched only from inside the drain loop, so a plain {@link ArrayDeque} is enough.
         */
        private final Deque<String> replaying = new ArrayDeque<>();

        /**
         * Set by a {@code Resend:} and cleared by the {@code ok} that follows it.
         *
         * <p>Marlin answers a rejected block with {@code Error:}, {@code Resend: n}, then
         * {@code ok}. That {@code ok} is the firmware saying it is ready again - it acknowledges no
         * block, and letting it reach {@link GSendWindow#acknowledge()} would discard the very
         * block the rewind just made outstanding again.
         */
        private final AtomicBoolean rewinding = new AtomicBoolean();

        /**
         * Set once the subscriber has been told the stream is over, by either route.
         *
         * <p>{@code onComplete} and {@code onError} are final, and the drain loop can now be
         * started by a reply - an {@code ok} or a {@code Resend:} that arrives after
         * {@link HostTerminal#stop()} would otherwise find whatever was parked in {@link #pending}
         * and write it behind the terminal signal. It also makes {@link #cancel()} idempotent,
         * which a cancel has to be.
         *
         * <p>It is a flag and not serialisation: a cancel that lands between the check and the
         * write still races, because cancellation does not go through the drain loop. Closing that
         * properly means giving the loop the terminal signal to emit as well, which is a change to
         * the shape of this class rather than to this field.
         */
        private final AtomicBoolean terminated = new AtomicBoolean();

        private final Supplier<String> report = () -> format("(s=%s, r=%s u=%s w=%s n=%d/%d b=%s)",
                subscriptionRequested, requestedFromGCode, awaitingFrame, sentWithoutAck,
                window.getInFlight(), window.getCapacity(), backpressure);

        public CommandProcessor(Publisher<String> gCode) {
            this.gcode = gCode;
        }

        /**
         * One {@code ok}: a free slot in the printer's buffer, and - usually - one block released
         * from the send window.
         *
         * <p>Usually, because the {@code ok} that closes a resend handshake is neither. It frees
         * nothing this host is still counting: the firmware discarded everything from the rejected
         * block onwards without acknowledging it, and {@link #rewind} has already said so. Taking
         * it as an acknowledgement would release a block the printer never accepted, and that block
         * is the one it just asked for.
         */
        public void handleBackpressure(OkRs<?> ok) {
            if (ok instanceof AdvancedOkRs advanced) {
                backpressure = advanced;
            }
            if (rewinding.compareAndSet(true, false)) {
                log.info("OUT: ok closing a resend - credit only, it acknowledges no block: {} {}",
                        ok.encode(), report.get());
            } else {
                sentWithoutAck.dec();
                windowEvents.add(ACK);
                log.info("OUT: backpressure applied: {} {}", ok.encode(), report.get());
            }
            outSide.processRequest();
        }

        /**
         * Nothing is in flight that the printer will acknowledge, so its buffer is all of it.
         *
         * <p>The send window is deliberately left alone - see {@link #window}. A stalled machine
         * has not accepted those blocks, and a {@code Resend:} for them is the likeliest thing to
         * come next.
         */
        void clearInFlight() {
            sentWithoutAck.reset();
        }

        /**
         * How many more commands may be pulled: the subscriber's demand, bounded by the printer's
         * buffer and by the send window, whichever is tightest.
         *
         * <p>The third term is new with the window and is what makes a refused {@code send}
         * impossible: a block stays in the window until it is acknowledged, so the host may not
         * have more outstanding than it can replay. Both printer terms subtract the commands
         * already spoken for - asked of the source ({@code requestedFromGCode}) and received but
         * not framed yet ({@code awaitingFrame}) - because each of those is a block that will
         * occupy a slot the moment it arrives.
         *
         * <p>Zero while a replay is waiting for demand. Those blocks carry numbers the firmware is
         * sitting on, and nothing newer may be pulled, let alone written, in front of them.
         */
        long canProcess() {
            if (!replaying.isEmpty()) {
                return 0;
            }
            long spokenFor = requestedFromGCode.get() + awaitingFrame.get();
            return min(subscriptionRequested.get(),
                    min(backpressure.getBlockQueue() - sentWithoutAck.get() - spokenFor,
                            window.getCapacity() - window.getInFlight() - spokenFor));
        }

        /**
         * Moves as much of the subscriber's demand upstream as the printer currently has room for.
         *
         * <p>A drain loop, and the only place the three counters are moved against each other.
         * Atomic counters make each one safe on its own; they do not make
         * {@code canProcess() -> inc/dec -> request(n)} one step, and that sequence has to be one
         * step or two threads - a {@code request(n)} from the subscriber and an {@code ok} off the
         * netty loop - can each read the same room and each claim it, asking the source for twice
         * what the printer can hold.
         *
         * <p>So {@code wip} serialises the section the Reactive Streams way rather than a lock: the
         * first caller in runs the loop, a caller that arrives while it is running leaves a mark
         * and returns, and the thread inside goes round again to pick the mark up. That also keeps
         * re-entrancy honest - {@link #onNext} calls this from inside {@code subscription.request},
         * and the nested call now defers to the loop already on the stack instead of interleaving
         * with it.
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
         * Applies the acknowledgements and resend requests the inbound side parked, in order.
         *
         * <p>Drain loop only - see the class note. The order is the protocol's own: an {@code ok}
         * that arrived before a {@code Resend:} released a block the firmware really had taken, and
         * applying the two the other way round would rewind over it.
         */
        private void applyWindowEvents() {
            for (Integer event; (event = windowEvents.poll()) != null; ) {
                if (event == ACK) {
                    if (!window.acknowledge()) {
                        log.debug("OUT: ok with nothing outstanding in the send window {}", report.get());
                    }
                } else {
                    rewind0(event);
                }
            }
        }

        /**
         * Spec 8.5 step 5: put the blocks from {@code from} onwards back in hand, byte for byte.
         *
         * <p>The printer's buffer count is rebuilt rather than adjusted. A {@code Resend:} says the
         * firmware threw away the rejected block and everything after it without acknowledging any
         * of it, so whatever this host believed it was occupying is no longer true; what it will
         * occupy is the replay, and nothing else.
         *
         * <p>An empty replay means the session has drifted further than a replay can repair - the
         * firmware is asking for a block this host no longer holds, which in a well-behaved session
         * cannot happen, since a block is only released by the {@code ok} that accepted it. There is
         * no partial answer worth sending; {@code GSendWindow.reset} and an {@code M110} is the
         * repair, and it is not this method's to decide.
         */
        private void rewind0(int from) {
            List<String> replay = window.resendFrom(from);
            if (replay.isEmpty()) {
                log.warn("OUT: Resend: {} - not held by the send window, so the sequence cannot be"
                        + " repaired from here; the session is out of sync {}", from, report.get());
                return;
            }
            sentWithoutAck.reset();
            sentWithoutAck.inc(replay.size());
            log.warn("OUT: Resend: {} - replaying {} block(s) {}", from, replay.size(), report.get());
            replaying.addAll(replay);
        }

        /**
         * Writes what is owed, replay first and then what was pulled since.
         *
         * <p>Replay first because its blocks carry numbers the firmware is waiting for, and a
         * newer block written in front of one of them is the gap Marlin rejects outright.
         *
         * <p>A replayed block takes a unit of the subscriber's demand of its own. The unit the
         * block consumed when it was first written is spent; this is a second write, and the
         * Reactive Streams rule that no {@code onNext} goes out unasked does not have an exception
         * for a retransmission. A pulled command spends its unit earlier, in
         * {@link #processRequest()}, where the pull itself is accounted.
         */
        private void write() {
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
            windowEvents.add(lineNumber);
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

        /** Idempotent, and the stream stays over: a second call and a late reply both do nothing. */
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
            subscriber.onComplete();
        }

        @Override
        public void onSubscribe(Subscription s) {
            log.info("OUT onSubscription {}", s);
            this.subscription = s;
        }

        /**
         * One command off the source: parked, not written.
         *
         * <p>This runs on whatever thread the source produces on - {@link PublisherQueue} pushes
         * from {@code addPublisher} as readily as from {@code request} - so it may neither frame
         * the command nor write it. It hands both to the drain loop, which is the only place the
         * numbering, the window and the wire are touched.
         *
         * <p>The command is counted into {@link #awaitingFrame} <em>before</em>
         * {@link #requestedFromGCode} is counted down, so that the room it is about to occupy is
         * never momentarily unaccounted for in {@link #canProcess()}.
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

        @Override
        public void onComplete() {
            log.info("GCode Complete {}", report.get());
            //subscriber.onComplete();
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
         * Routes one decoded reply: half of it into the send window, half onto the bus.
         *
         * <p>Matched on the family interfaces rather than on concrete classes, because that is what
         * they are for. {@link OkRs} covers the bare, the advanced and the temperature-carrying
         * {@code ok} - all three are acknowledgements and all three free a slot. {@link BusyRs}
         * covers the bare {@code busy:} and Marlin's {@code echo:busy:}. A reply type added to
         * {@code :gcode} under either interface arrives here already routed.
         *
         * <p>The arms are not a partition and have to be read as a sequence: {@code OkTemperatureRs}
         * is an {@link OkRs} first and a temperature report second, which is the acknowledgement
         * the window is waiting for.
         *
         * <p><strong>{@code ErrorRs} has no arm, deliberately.</strong> It falls to the default and
         * is published as a value, because on a Marlin an error is routine protocol traffic and the
         * printer carries on afterwards:
         *
         * <pre>
         * &gt;&gt; N3 M105*1
         * &lt;&lt; Error:checksum mismatch, Last Line: 2
         * &lt;&lt; Resend: 3
         * &lt;&lt; ok
         * </pre>
         *
         * <p>This used to call {@code tryEmitError}, which terminates the {@code Sinks.Many}
         * behind {@link HostTerminal#messageFlux()} - so one mistyped checksum killed the reply bus
         * for the rest of the session, for every current and future subscriber, while the printer
         * went on answering normally. A terminal signal on that flux now means one thing only: the
         * inbound side is gone, which is {@link #onError} and {@link #onComplete} below.
         *
         * <p>Which errors are fatal is not decided here. {@code :gcode} keeps the machine's own
         * words as text on purpose - the spec sorts errors into recoverable, non-fatal and
         * shut-the-machine-down on prose it only gives as examples - and that is a decision for the
         * host, which can now see every error it needs to make it.
         */
        @Override
        public void onNext(GRs<?> reply) {
            log.info("IN: onNext({})", reply);
            switch (reply) {
                case OkRs<?> ok -> onOk(ok);
                case BusyRs<?> busy -> onBusy(busy);
                case WaitRs _ -> onWait();
                case ResendRs resend -> onResend(resend.getLineNumber());
                default -> emit(ReplyEvents.of(reply));
            }
        }

        @Override
        public void onError(Throwable t) {
            log.error("IN: onError()", t);
            emitError(t);
        }

        @Override
        public void onComplete() {
            log.warn("IN: onComplete()");
            Sinks.EmitResult result = replays.tryEmitComplete();
            if (result.isFailure()) {
                log.warn("IN: completion dropped ({})", result);
            }
        }
    }

}
