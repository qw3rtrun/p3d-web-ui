package org.qw3rtrun.p3d.terminal;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.PriorityQueue;
import java.util.Queue;

@Slf4j
public class PublisherQueue<T, P extends Comparable<P>> implements Publisher<T>, Subscription {

    private final Queue<PublisherDescr> publishers = new PriorityQueue<>();

    private Subscriber<? super T> subscriber;

    private final Requested requested = new Requested();

    public long getUnsatisfiedRequestCount() {
        return requested.get();
    }

    public void addPublisher(P priority, Publisher<? extends T> publisher) {
        publishers.add(new PublisherDescr(publisher, priority));
        request0(0);
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        this.subscriber = s;
        s.onSubscribe(this);
    }

    void onNext(PublisherDescr p, T t) {
        subscriber.onNext(t);
    }

    void onError(PublisherDescr p, Throwable t) {
        p.cancel();
        publishers.remove(p);
        subscriber.onError(t);
        request0(p.requested.get());
    }

    void onComplete(PublisherDescr p) {
        publishers.remove(p);
        request0(p.requested.get());
    }

    @Override
    public void request(long n) {
        log.debug("requested {}", n);
        request0(n);
    }

    private synchronized void request0(long n) {
        requested.inc(n);
        if (requested.get() < 1) {
            return;
        }
        var p = publishers.peek();
        if (p != null) {
            log.debug("requesting {} with {}", p.publisher, requested);
            p.request(requested.reset());
        } else {
            log.debug("unsatisfied {}", requested);
        }
    }

    @Override
    public void cancel() {
        log.debug("cancel()");
        this.requested.reset();
        publishers.forEach(PublisherDescr::cancel);
    }

    @RequiredArgsConstructor
    private class PublisherDescr implements Subscriber<T>, Subscription, Comparable<PublisherDescr> {
        private final Publisher<? extends T> publisher;
        private Subscription subscription;
        private final P priority;

        @Getter
        Requested requested = new Requested();

        void subscribe() {
            publisher.subscribe(this);
        }

        @Override
        public void onSubscribe(Subscription s) {
            this.subscription = s;
        }

        @Override
        public void onNext(T t) {
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
        public void request(long n) {
            requested.inc(n);
            if (subscription == null) {
                subscribe();
            }
            subscription.request(n);
        }

        @Override
        public void cancel() {
            this.requested.reset();
            if (subscription != null) {
                subscription.cancel();
            }
        }

        @Override
        public int compareTo(PublisherDescr o) {
            return this.priority.compareTo(o.priority);
        }
    }
}


