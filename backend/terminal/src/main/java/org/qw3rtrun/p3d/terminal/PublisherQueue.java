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
 * <p>An emergency stop has to reach the wire ahead of a print already streaming, and a temperature
 * query has to interleave with it: a long background source is preempted by a short urgent one and
 * resumes once the urgent one is exhausted.
 *
 * <p>Demand accounting is atomics plus a drain loop - the mechanism {@code CommandProcessor} uses
 * and the only one this module has. It replaces a {@code synchronized request0} guarding a
 * {@link java.util.PriorityQueue} that {@code onNext}/{@code onComplete}/{@code onError}/
 * {@code addPublisher} mutated unguarded - a real race, since preempting a running print means
 * {@code addPublisher} on a request thread concurrently with the netty loop draining. The drain
 * loop also closes the compound race a lock alone could not: {@code peek} then
 * {@code requested.reset()} then {@code request(n)} is three steps, and two threads peeking the
 * same head would each claim the demand, handing one publisher a zero - which rule 3.9 makes an
 * error rather than a no-op.
 *
 * <p>One source may ask not to be interleaved at all - see {@link #addExclusivePublisher}. Preemption
 * is this class's whole purpose, and an SD upload is the one source that must never suffer it,
 * because between {@code M28} and {@code M29} the wire is a file sink rather than a command channel.
 */
@Slf4j
public class PublisherQueue<T, P extends Comparable<P>> implements Publisher<T>, Subscription {

    /**
     * {@link PriorityBlockingQueue}, not external locking: {@code offer}/{@code peek}/{@code remove}
     * are each atomic, so the drain loop only has to serialise the decisions it makes about the
     * head, not every touch of the heap - adding a source and draining one genuinely race.
     */
    private final Queue<PublisherDescr> publishers = new PriorityBlockingQueue<>();

    /**
     * Tie-break for the heap, which is not stable. Two sources at the same priority are the normal
     * case (two interactive commands, a job and a temperature poll); without arrival order a user
     * who pressed Home then Set Temperature could get them the other way round.
     */
    private final AtomicLong sequence = new AtomicLong();

    /** Volatile because {@link #subscribe} runs on whichever thread subscribed, and the signal
     * paths run on whichever thread a source produces on. */
    private volatile Subscriber<? super T> subscriber;

    private final Requested requested = new Requested();

    /** Set once, by whatever ends the stream. Every signalling path checks it first. */
    private final AtomicBoolean terminated = new AtomicBoolean();

    /**
     * The exclusive source that currently has the connection to itself, or null. Written only
     * inside {@link #drain()} (single-threaded by the {@code wip} guard); {@code volatile} because
     * {@link #onNext} reads it on whichever thread a source produces on.
     */
    private volatile PublisherDescr exclusiveHolder;

    /** The drain guard - zero when nobody is inside {@link #drain()}. */
    private final AtomicInteger wip = new AtomicInteger();

    public long getUnsatisfiedRequestCount() {
        return requested.get();
    }

    /**
     * Adds a source, and returns a {@link Mono} that completes when the source is exhausted and
     * errors when it does - the queue used to remove a finished source and tell nobody, so a print
     * that reached end of file was invisible to the layer that queued it. A caller that does not
     * care may ignore the result; an unsubscribed {@code Mono} costs one sink.
     */
    public Mono<Void> addPublisher(P priority, Publisher<? extends T> publisher) {
        return add(priority, publisher, false);
    }

    /**
     * Adds a source that must not be interleaved with anything else - for {@code M28}: an SD upload
     * opens a file on the card and every line sent after it is <em>written into that file</em>
     * rather than executed, until {@code M29} closes it, so a command from any other source that
     * reaches the wire in between is silently stored and surfaces later as nonsense mid-print.
     *
     * <p>Priority alone cannot prevent that: queueing above everything else keeps the upload at the
     * head, but a source handed demand <em>before</em> the upload was queued still holds it and may
     * emit at any moment. So this source instead:
     *
     * <ul>
     *   <li>waits to start until it is the head <em>and</em> no other source holds demand - the
     *       demand it would have been given is withheld meanwhile rather than handed elsewhere, so
     *       the wait is only as long as it takes every other source to spend what it holds;
     *   <li>then keeps the connection, with everything behind it waiting, until it completes;
     *   <li>is aborted, not paused, by a source of strictly higher priority - the returned
     *       {@link Mono} errors with {@link Preempted}. An {@code M112} cannot wait for a transfer
     *       to finish, Marlin's emergency parser reads it out of the serial bytes ahead of the SD
     *       redirect regardless, and by then the same bytes are already written into the open file -
     *       so the transfer is corrupt the moment anything else reaches the wire and cannot be
     *       resumed; the caller is told rather than left to produce a file that looks complete and
     *       is not.
     * </ul>
     *
     * <p>Queue an upload at {@link org.qw3rtrun.p3d.terminal.msg.Priority#REALTIME} - the slot with
     * only {@code EMERGENCY} above it, so an emergency stop is the one thing that can take the wire
     * away.
     *
     * <p>A source that holds demand and neither emits nor completes keeps this one waiting
     * indefinitely; every source today is request-driven and bounded by the send window, so in
     * practice the wait is a few blocks, but there is no timeout here - a deadline belongs to the
     * layer that knows what the transfer is for.
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
     * Hands all outstanding demand to the highest-priority source, one thread at a time - except an
     * exclusive source not yet ready to start, which gets nothing and leaves the demand in the pool
     * (the mechanism {@link #addExclusivePublisher} depends on). The {@code wip} guard also makes
     * re-entrancy safe: {@link PublisherDescr#onComplete} calls this from inside the drain that
     * caused the completion, and the nested call defers to the loop already on the stack.
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
     * The source that may be given demand right now, or null when none may be. Drain loop only, so
     * {@link #exclusiveHolder} needs no atomics here - the {@code wip} guard admits one thread and
     * its hand-off carries the happens-before. In order: a holder no longer at the head has been
     * outranked and is aborted (see {@link #abortExclusive}); an exclusive head waits while anyone
     * else still holds demand, since that demand can still be spent into the open file; otherwise
     * the head runs, becoming the holder if it is exclusive.
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

    /** Any source other than {@code except} that could still emit, or null when none can. A weakly
     * consistent iteration over the heap is enough - re-asked on every emission and completion. */
    private PublisherDescr sourceStillHoldingDemand(PublisherDescr except) {
        for (var d : publishers) {
            if (d != except && d.requested.get() > 0) {
                return d;
            }
        }
        return null;
    }

    /**
     * Something more urgent arrived: the transfer is over and the caller is told. Unlike
     * {@link #onError} this does not end the queue - only this one source dies - and unspent demand
     * goes back to the pool, the way a completion returns it.
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
     * What a source queued with {@link #addExclusivePublisher} fails with when preempted - a type of
     * its own, not a plain {@code IllegalStateException}, so the caller can tell it apart from a
     * source that failed on its own: the aborted upload has left a file open on the card that the
     * next {@code M29} or reset closes as truncated, and the caller needs to say so rather than
     * retry blindly.
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

        // Spending the last unit may be what an exclusive source was waiting for, and only a
        // completion drains by itself - so ask narrowly here instead of draining every value.
        if (exclusiveHolder == null && p.requested.get() == 0) {
            var head = publishers.peek();
            if (head != null && head.exclusive) {
                drain();
            }
        }
    }

    /**
     * A source failed, which ends the whole queue, not just that source: the subscriber here is the
     * wire, and there is no way to tell it that one of several sources died without the print
     * silently missing the lines that source would have produced. {@link #terminate} sets its flag
     * before signalling, which is what stops this from pushing an {@code onNext} after the
     * subscriber has already been told the stream is over.
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
     * A source is exhausted: forget it, return its unspent demand, tell whoever queued it. The
     * queue itself does not complete - it outlives every source, ending only when the socket does.
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
     * Ends the stream once, cancels every source, and optionally signals the subscriber - one place,
     * so "has this stream ended" has one answer. A null signal is a cancel (rule 2.4: nothing is
     * signalled after it). Every source still queued is <em>failed</em>, not just cancelled, so the
     * {@link Mono} {@link #addPublisher} returns does not simply go unsignalled forever - an upload's
     * whole report to its caller is that signal, and "the connection went away mid-transfer" is the
     * one case it must not lose.
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
         * Demand asked for before {@code onSubscribe} arrived. This used to be
         * {@code if (subscription == null) subscribe(); subscription.request(n)} on the next line,
         * which NullPointers for any source that does not call {@code onSubscribe} synchronously
         * from inside {@code subscribe} - {@code Mono.fromFuture}, a {@code publishOn}, a file read.
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
         * Passes on accumulated demand once there is a subscription to pass it to. Safe without a
         * lock because {@code request} adds to {@code pending} before reading {@code subscription}
         * and {@code onSubscribe} publishes {@code subscription} before draining - whichever runs
         * second sees the other's work, and {@code reset} hands the demand to exactly one of them.
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
         * One value from this source. A source that emits more than it was asked for is cancelled
         * and its excess dropped, loudly: forwarding it would be an {@code onNext} the send window
         * downstream never asked for, and Marlin answers an overrun by dropping commands in silence
         * - a loud drop here is the better of two bad outcomes, and a well-behaved source never
         * reaches it.
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
