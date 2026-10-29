package org.qw3rtrun.p3d.terminal;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Queue;
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
        var descr = new PublisherDescr(publisher, priority, sequence.getAndIncrement());
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
     * <p><strong>All of it goes to the head and none is held back.</strong> That is what makes a
     * preemption work: the demand follows whatever is now highest priority, and what the previous
     * head did not spend comes back to the pool when it completes.
     */
    private void drain() {
        if (wip.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        for (; ; ) {
            if (!terminated.get() && requested.get() > 0) {
                var head = publishers.peek();
                if (head != null) {
                    long give = requested.reset();
                    if (give > 0) {
                        log.debug("requesting {} from {}", give, head.publisher);
                        head.request(give);
                    }
                } else {
                    log.debug("unsatisfied {}", requested);
                }
            }
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                return;
            }
        }
    }

    void onNext(PublisherDescr p, T t) {
        if (terminated.get()) {
            return;
        }
        subscriber.onNext(t);
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
     */
    private void terminate(Consumer<Subscriber<? super T>> signal) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        requested.reset();
        publishers.forEach(PublisherDescr::cancel);
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

        PublisherDescr(Publisher<? extends T> publisher, P priority, long seq) {
            this.publisher = publisher;
            this.priority = priority;
            this.seq = seq;
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
