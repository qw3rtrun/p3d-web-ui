package org.qw3rtrun.p3d.terminal;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Several sources of G-code, drained highest priority first into one subscriber.
 *
 * <p>An emergency stop has to reach the wire ahead of a print that is already streaming, and a
 * temperature query has to interleave with it. That is the whole job: a long background source is
 * preempted by a short urgent one, and resumes when the urgent one is exhausted.
 *
 * <p><strong>Demand accounting here is atomics plus a drain loop, which is the mechanism
 * {@code CommandProcessor} uses and the only one this module has.</strong> It was half of one:
 * {@code request0} was {@code synchronized} while {@code onNext}, {@code onComplete},
 * {@code onError} and {@code addPublisher} mutated the same {@link java.util.PriorityQueue} without
 * it - and a {@code PriorityQueue} is not thread-safe. That is not theoretical in the case this
 * class exists for: preempting a running print means {@code addPublisher} on a request thread while
 * the netty loop is draining, which is a concurrent {@code offer} against a {@code poll} on a heap
 * that assumes neither.
 *
 * <p>The same loop closes the compound race the lock could not: {@code peek} then
 * {@code requested.reset()} then {@code request(n)} is three steps, and two threads that each
 * peeked the same head would each have claimed the demand - one of them handing the publisher a
 * zero, which Reactive Streams rule 3.9 makes an error rather than a no-op.
 *
 * <p><strong>One source may ask not to be interleaved at all</strong> - see
 * {@link #addExclusivePublisher}. Preemption is this class's whole purpose and an SD upload is the
 * one thing it must not do, because between {@code M28} and {@code M29} the wire is a file sink
 * rather than a command channel.
 */
@Slf4j
public class PublisherQueue<T, P extends Comparable<P>> implements Publisher<T>, Subscription {

    /**
     * Thread-safe, because adding a source and draining one genuinely race.
     *
     * <p>{@link PriorityBlockingQueue} rather than external locking: its {@code offer},
     * {@code peek} and {@code remove} are each atomic, so the drain loop only has to serialise the
     * decisions it makes <em>about</em> the head rather than every touch of the heap.
     */
    private final Queue<PublisherDescr> publishers = new PriorityBlockingQueue<>();

    /**
     * Ties a descriptor's position in the heap to the order it was added.
     *
     * <p>{@link PriorityBlockingQueue} is not stable, and two sources at the same priority are the
     * normal case - two interactive commands, or a job and a temperature poll. Without a
     * tie-break they come out in whatever order the heap happens to hold them, so a user who
     * pressed Home and then Set Temperature could get them the other way round.
     */
    private final AtomicLong sequence = new AtomicLong();

    /** Volatile because {@link #subscribe} runs on whichever thread subscribed, and the signal
     * paths run on whichever thread a source produces on. */
    private volatile Subscriber<? super T> subscriber;

    private final Requested requested = new Requested();

    /** Set once, by whatever ends the stream. Every signalling path checks it first. */
    private final AtomicBoolean terminated = new AtomicBoolean();

    /**
     * The exclusive source that currently has the connection to itself, or null.
     *
     * <p>Written only from inside {@link #drain()}, which is single-threaded by its {@code wip}
     * guard; {@code volatile} because {@link #onNext} reads it on whichever thread a source
     * produces on.
     */
    private volatile PublisherDescr exclusiveHolder;

    /** The drain guard - zero when nobody is inside {@link #drain()}. */
    private final AtomicInteger wip = new AtomicInteger();

    public long getUnsatisfiedRequestCount() {
        return requested.get();
    }

    /**
     * Adds a source, and answers with when it finishes.
     *
     * <p><strong>The returned {@link Mono} is how a caller learns that its source is
     * exhausted</strong> - it completes when the publisher completes and errors when it errors.
     * Nothing needed that until now, which is why nothing had it: the queue removed a finished
     * source and told nobody, so a print that reached the end of its file was invisible to the
     * layer that queued it. Plan item C3 is the first caller that has to know, and C2's
     * {@code JobService.complete} is what it will call.
     *
     * <p>A caller that does not care ignores it; an unsubscribed {@code Mono} costs one sink.
     */
    public Mono<Void> addPublisher(P priority, Publisher<? extends T> publisher) {
        return add(priority, publisher, false);
    }

    /**
     * Adds a source that must not be interleaved with anything else.
     *
     * <p><strong>This exists for {@code M28}.</strong> An SD upload opens a file on the card and
     * every line sent after it is <em>written into that file</em> rather than executed, until
     * {@code M29} closes it. So a command from any other source that reaches the wire in between
     * does not run - it is stored, and the fault surfaces much later as a line of nonsense in the
     * middle of a print. Nothing catches it and nothing reports it.
     *
     * <p><strong>Priority alone does not prevent that</strong>, which is why this is a mechanism
     * rather than a convention. Queueing an upload above everything else does keep it at the head
     * of the heap, but a source that was handed demand <em>before</em> the upload was queued still
     * holds that demand and may emit on it at any moment - a print already streaming at
     * {@code BACKGROUND}, or an interactive command given a unit a moment earlier.
     *
     * <h2>What the caller gets</h2>
     *
     * <ul>
     *   <li><strong>It waits to start.</strong> It takes the connection when it is the head
     *       <em>and</em> no other source is holding demand, and not before. The demand it would
     *       have been given is withheld meanwhile rather than handed elsewhere, so the wait is
     *       short: every other source either spends what it holds or completes.
     *   <li><strong>Then it keeps the connection</strong> until it completes. Everything queued
     *       behind it waits, however much demand arrives.
     *   <li><strong>A source of strictly higher priority preempts it, and that aborts it.</strong>
     *       The returned {@link Mono} errors with {@link Preempted}; see below.
     * </ul>
     *
     * <h2>Why a preemption aborts rather than pauses</h2>
     *
     * An emergency stop cannot wait. A real sliced file is tens of thousands of lines at one round
     * trip each, so an {@code M112} held until the transfer finished would arrive minutes late,
     * which is not a stop. It therefore goes through - and Marlin's emergency parser reads it out
     * of the serial bytes as they arrive, ahead of the command queue and ahead of the SD redirect,
     * so it does fire. The same bytes are also written into the open file.
     *
     * <p>So the file is corrupt the moment anything else reaches the wire, and a half-written
     * transfer cannot be resumed from where it stopped. Carrying on would produce a file that
     * looks complete and is not. The caller is told instead.
     *
     * <p><strong>Queue an upload at {@link org.qw3rtrun.p3d.terminal.msg.Priority#REALTIME}</strong>,
     * which is the slot with only {@code EMERGENCY} above it - so an emergency stop is the one
     * thing that can take the wire away, and that is the one thing that should be able to.
     *
     * <h2>The one way it can be delayed indefinitely</h2>
     *
     * A source that is holding demand and neither emits nor completes keeps an exclusive source
     * waiting. Every source this queue has today is request-driven and spends what it is given
     * promptly, and the amount outstanding is bounded by the send window, so in practice the wait
     * is a few blocks. It is still a wait with no timeout here: a deadline belongs to the layer
     * that knows what the transfer is for, not to the queue.
     */
    public Mono<Void> addExclusivePublisher(P priority, Publisher<? extends T> publisher) {
        return add(priority, publisher, true);
    }

    private Mono<Void> add(P priority, Publisher<? extends T> publisher, boolean exclusive) {
        var descr = new PublisherDescr(publisher, priority, sequence.getAndIncrement(), exclusive);
        if (terminated.get()) {
            log.debug("the queue is over, so {} was not added", publisher);
            return Mono.empty();
        }
        publishers.add(descr);
        drain();
        return descr.finished.asMono();
    }

    /**
     * One subscriber, and a second is told so rather than silently replacing the first.
     *
     * <p>Reactive Streams rule 1.10: this publisher supports one subscription, and the way to
     * decline another is {@code onError} on the newcomer. Assigning over the field - which is what
     * this did - left the first subscriber attached to a queue that would never signal it again.
     */
    @Override
    public void subscribe(Subscriber<? super T> s) {
        if (this.subscriber != null) {
            s.onSubscribe(EmptySubscription.INSTANCE);
            s.onError(new IllegalStateException("PublisherQueue supports one subscriber"));
            return;
        }
        this.subscriber = s;
        s.onSubscribe(this);
    }

    @Override
    public void request(long n) {
        if (n <= 0) {
            // Rule 3.9: a non-positive request is a caller error, and the way to report it is an
            // onError rather than ignoring it and leaving the caller waiting for values.
            terminate(t -> t.onError(new IllegalArgumentException(
                    "Reactive Streams rule 3.9: request was " + n + ", which must be positive")));
            return;
        }
        log.debug("requested {}", n);
        requested.inc(n);
        drain();
    }

    /**
     * Hands the outstanding demand to the highest-priority source, one thread at a time.
     *
     * <p>The guard is the Reactive Streams way rather than a lock: the first caller in runs the
     * loop, a caller that arrives while it is running leaves a mark and returns, and the thread
     * inside goes round again to pick the mark up. That also keeps re-entrancy honest -
     * {@link PublisherDescr#onComplete} calls this from inside the drain that caused the
     * completion, and the nested call defers to the loop already on the stack.
     *
     * <p><strong>All of it goes to the head and none is held back</strong>, with one exception -
     * an exclusive source that is not ready to start is given nothing and the demand stays in the
     * pool. That is the whole of {@link #addExclusivePublisher}: handing it elsewhere is what the
     * upload must not have happen.
     *
     * <p>Otherwise the demand follows whatever is now highest priority, and what the previous head
     * did not spend comes back to the pool when it completes.
     */
    private void drain() {
        if (wip.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        for (; ; ) {
            if (!terminated.get()) {
                var head = headThatMayRun();
                if (head != null && requested.get() > 0) {
                    long give = requested.reset();
                    if (give > 0) {
                        log.debug("requesting {} from {}", give, head.publisher);
                        head.request(give);
                    }
                } else if (head == null && requested.get() > 0) {
                    log.debug("unsatisfied {}", requested);
                }
            }
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                return;
            }
        }
    }

    /**
     * The source that may be given demand right now, or null when none may be.
     *
     * <p>Drain loop only, which is what makes {@link #exclusiveHolder} safe to read and write here
     * without atomics: the {@code wip} guard admits one thread, and the hand-off through it carries
     * the happens-before.
     *
     * <p>Three decisions, in the order they have to be made:
     *
     * <ol>
     *   <li><strong>A holder that is no longer the head has been outranked</strong>, because the
     *       heap puts the most urgent source first and nothing else moves it. That is a preemption,
     *       and a preempted transfer is aborted rather than resumed - see
     *       {@link #abortExclusive}.
     *   <li><strong>An exclusive head waits while anyone else holds demand.</strong> Demand already
     *       handed out is demand that can still be spent, and a line spent on it during a transfer
     *       is written into the file. The pool keeps what it would have been given, so the wait
     *       ends by itself the moment the last outstanding unit is spent or its source completes.
     *   <li><strong>Otherwise the head runs</strong>, exclusive or not, and an exclusive one
     *       becomes the holder as it starts.
     * </ol>
     */
    private PublisherDescr headThatMayRun() {
        var head = publishers.peek();
        var holder = exclusiveHolder;
        if (holder != null) {
            if (head == holder) {
                return holder;
            }
            abortExclusive(holder, head);
            head = publishers.peek();
        }
        if (head == null || !head.exclusive) {
            return head;
        }
        var blocker = sourceStillHoldingDemand(head);
        if (blocker != null) {
            log.debug("OUT: {} is waiting for the connection - {} still holds {} unit(s) of demand",
                    head.publisher, blocker.publisher, blocker.requested);
            return null;
        }
        exclusiveHolder = head;
        log.info("OUT: {} has the connection to itself until it finishes", head.publisher);
        return head;
    }

    /**
     * Any source other than {@code except} that could still emit, or null when none can.
     *
     * <p>A weakly consistent iteration over the heap is enough: a source added during it is one
     * that has been given no demand yet, and this is re-asked on every emission and every
     * completion anyway.
     */
    private PublisherDescr sourceStillHoldingDemand(PublisherDescr except) {
        for (var d : publishers) {
            if (d != except && d.requested.get() > 0) {
                return d;
            }
        }
        return null;
    }

    /**
     * Something more urgent arrived, so the transfer is over and the caller is told.
     *
     * <p>It does <strong>not</strong> end the queue, unlike {@link #onError} - the session is fine
     * and the preempting source is about to use it. Only this one source dies.
     *
     * <p>The unspent demand goes back to the pool, the way a completion returns it, so the
     * preempting source is not left waiting for a request that was already made.
     */
    private void abortExclusive(PublisherDescr holder, PublisherDescr preemptedBy) {
        exclusiveHolder = null;
        publishers.remove(holder);
        long unspent = holder.requested.get();
        holder.cancel();
        requested.inc(unspent);
        log.warn("OUT: {} had the connection to itself and was preempted by {} - aborting it,"
                        + " because a transfer that is already half-written cannot be resumed",
                holder.publisher, preemptedBy == null ? "a cancellation" : preemptedBy.publisher);
        holder.finished.tryEmitError(new Preempted(holder.publisher, preemptedBy));
    }

    /**
     * What a source queued with {@link #addExclusivePublisher} fails with when it is preempted.
     *
     * <p>A type rather than a plain {@code IllegalStateException}, because the caller has to tell
     * this apart from a source that failed on its own: an upload aborted this way has left a file
     * open on the card that the next {@code M29} or reset will close as a truncated one, and
     * whatever is driving it needs to say so rather than retry blindly.
     */
    public static final class Preempted extends RuntimeException {

        Preempted(Object source, Object preemptedBy) {
            super(source + " had exclusive use of the connection and was preempted by "
                    + (preemptedBy == null ? "a cancellation" : preemptedBy));
        }
    }

    void onNext(PublisherDescr p, T t) {
        if (terminated.get()) {
            return;
        }
        subscriber.onNext(t);

        // A source that has just spent its last unit may be the only thing an exclusive source is
        // waiting for, and nothing else would notice: a completion drains, an emission on its own
        // does not. Asked narrowly rather than draining after every value, because this is the
        // per-value path.
        if (exclusiveHolder == null && p.requested.get() == 0) {
            var head = publishers.peek();
            if (head != null && head.exclusive) {
                drain();
            }
        }
    }

    /**
     * A source failed, which ends the queue.
     *
     * <p>Terminal for everything and not just for that source, because the subscriber here is the
     * wire: there is no way to tell it that one of several sources died, and carrying on would
     * mean a print silently missing the lines the failed source would have produced.
     *
     * <p>It used to signal {@code onError} and then call {@code request0}, which could push an
     * {@code onNext} at a subscriber already told the stream was over. {@link #terminate} is what
     * makes that impossible now: the flag is set before the signal and every path reads it.
     */
    void onError(PublisherDescr p, Throwable t) {
        publishers.remove(p);
        if (exclusiveHolder == p) {
            exclusiveHolder = null;
        }
        p.finished.tryEmitError(t);
        terminate(s -> s.onError(t));
    }

    /**
     * A source is exhausted: forget it, give its unspent demand back, and tell whoever queued it.
     *
     * <p>The queue itself does not complete. It outlives every source in it - a terminal session
     * ends when the socket does, not when the last command it happened to be carrying runs out.
     */
    void onComplete(PublisherDescr p) {
        publishers.remove(p);
        if (exclusiveHolder == p) {
            exclusiveHolder = null;
            log.info("OUT: {} is finished and the connection is shared again", p.publisher);
        }
        requested.inc(p.requested.reset());
        p.finished.tryEmitEmpty();
        drain();
    }

    @Override
    public void cancel() {
        log.debug("cancel()");
        terminate(null);
    }

    /**
     * Ends the stream once, cancels every source, and optionally signals the subscriber.
     *
     * <p>One place, so that "has this stream ended" has one answer. A null signal is a cancel -
     * the subscriber asked to stop and Reactive Streams rule 2.4 says it is not to be signalled
     * afterwards.
     *
     * <p><strong>Every source still in the queue is failed, not just cancelled.</strong> It used to
     * be cancelled and told nothing, so the {@link Mono} {@link #addPublisher} answers with simply
     * never signalled - a caller waiting on it waited for the life of the process. That was
     * invisible while nothing subscribed to those {@code Mono}s; an upload is the first thing whose
     * whole report to the user is that signal, and "the connection went away mid-transfer" is
     * precisely the case it must not lose. A job in the same position has the same claim on it.
     */
    private void terminate(Consumer<Subscriber<? super T>> signal) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        requested.reset();
        exclusiveHolder = null;
        publishers.forEach(p -> {
            p.cancel();
            p.finished.tryEmitError(new CancellationException(
                    p.publisher + " was still running when the queue ended"));
        });
        publishers.clear();
        var s = subscriber;
        if (signal != null && s != null) {
            signal.accept(s);
        }
    }

    /** What a declined second subscriber is given, so that it has one before its {@code onError}. */
    private enum EmptySubscription implements Subscription {
        INSTANCE;

        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    }

    private class PublisherDescr implements Subscriber<T>, Subscription, Comparable<PublisherDescr> {

        private final Publisher<? extends T> publisher;
        private final P priority;
        private final long seq;

        /** Whether this source may be interleaved with any other - see {@link #addExclusivePublisher}. */
        private final boolean exclusive;

        /** Completes when this source does - see {@link #addPublisher}. */
        private final Sinks.One<Void> finished = Sinks.one();

        /** Volatile: {@code onSubscribe} may arrive on another thread than {@code request}. */
        private volatile Subscription subscription;

        /** So that the source is subscribed exactly once however many threads ask at once. */
        private final AtomicBoolean subscribed = new AtomicBoolean();

        /** Demand given to this source and not yet spent - returned to the pool when it finishes. */
        private final Requested requested = new Requested();

        /**
         * Demand asked for before {@code onSubscribe} arrived.
         *
         * <p>This used to be {@code if (subscription == null) subscribe(); subscription.request(n)}
         * on the next line, which works only while every source calls {@code onSubscribe}
         * synchronously from inside {@code subscribe}. {@code Mono.just} does;
         * {@code Mono.fromFuture}, anything with a {@code publishOn}, and a file read - which is
         * exactly what plan item C3 queues here - do not, and the line NullPointers.
         */
        private final Requested pending = new Requested();

        PublisherDescr(Publisher<? extends T> publisher, P priority, long seq, boolean exclusive) {
            this.publisher = publisher;
            this.priority = priority;
            this.seq = seq;
            this.exclusive = exclusive;
        }

        @Override
        public void onSubscribe(Subscription s) {
            this.subscription = s;
            forwardPending();
        }

        @Override
        public void request(long n) {
            requested.inc(n);
            pending.inc(n);
            if (subscribed.compareAndSet(false, true)) {
                publisher.subscribe(this);
            }
            forwardPending();
        }

        /**
         * Passes on whatever demand has accumulated, once there is a subscription to pass it to.
         *
         * <p>The ordering is what makes this safe without a lock: {@code request} adds to
         * {@code pending} before it reads {@code subscription}, and {@code onSubscribe} publishes
         * {@code subscription} before it drains - so whichever runs second sees the other's work,
         * and {@code reset} hands the demand to exactly one of them.
         */
        private void forwardPending() {
            var s = subscription;
            if (s == null) {
                return;
            }
            long n = pending.reset();
            if (n > 0) {
                s.request(n);
            }
        }

        /**
         * One value from this source.
         *
         * <p><strong>A source that emits more than it was asked for is cancelled and its excess
         * dropped</strong>, loudly. Forwarding it would be an {@code onNext} the downstream never
         * asked for - and downstream here is the send window, whose whole job is to not put more
         * on the wire than the printer can hold. Marlin answers an overrun by dropping commands in
         * silence, so a loud drop here is the better of two bad outcomes, and a well-behaved source
         * never reaches it.
         */
        @Override
        public void onNext(T t) {
            if (requested.get() == 0) {
                log.error("OUT: {} emitted without demand, which Reactive Streams rule 1.1 forbids;"
                        + " dropping it and cancelling the source", publisher);
                cancel();
                PublisherQueue.this.onComplete(this);
                return;
            }
            requested.dec();
            PublisherQueue.this.onNext(this, t);
        }

        @Override
        public void onError(Throwable t) {
            PublisherQueue.this.onError(this, t);
        }

        @Override
        public void onComplete() {
            PublisherQueue.this.onComplete(this);
        }

        @Override
        public void cancel() {
            requested.reset();
            pending.reset();
            var s = subscription;
            if (s != null) {
                s.cancel();
            }
        }

        /** Priority first, then arrival order - see {@link PublisherQueue#sequence}. */
        @Override
        public int compareTo(PublisherDescr o) {
            int byPriority = this.priority.compareTo(o.priority);
            return byPriority != 0 ? byPriority : Long.compare(this.seq, o.seq);
        }
    }
}
