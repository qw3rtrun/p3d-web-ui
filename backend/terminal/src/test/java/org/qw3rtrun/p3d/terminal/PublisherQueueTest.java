package org.qw3rtrun.p3d.terminal;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.StepVerifierOptions;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qw3rtrun.p3d.terminal.msg.Priority.*;

@Slf4j
class PublisherQueueTest {

    private PublisherQueue<String, Priority> queue = new PublisherQueue<>();

    @Test
    void prebuild() {
        queue.addPublisher(EMERGENCY, Mono.just("0 Priority"));
        queue.addPublisher(REGULAR, Flux.just("2 Priority", "2 Priority"));
        queue.addPublisher(PERIODIC, Mono.just("1 Priority"));

        StepVerifier.create(queue)
                .thenRequest(10)
                .expectNext("0 Priority")
                .expectNext("1 Priority")
                .expectNext("2 Priority")
                .expectNext("2 Priority")
                .thenCancel().verify();
    }

    @Test
    void addInBetween() {
        queue.addPublisher(EMERGENCY, Mono.just("0 Priority"));
        queue.addPublisher(REGULAR, Flux.just("2 Priority", "2 Priority"));
        queue.addPublisher(PERIODIC, Mono.just("1 Priority"));

        StepVerifier.create(queue)
                .thenRequest(2)
                .expectNext("0 Priority")
                .expectNext("1 Priority")
                .then(() -> queue.addPublisher(EMERGENCY, Mono.just("0 Priority")))
                .expectNext("2 Priority")
                .expectNext("2 Priority")
                .expectNext("0 Priority")
                .thenCancel().verify();
    }

    @Test
    void addAfter() {
        queue.addPublisher(EMERGENCY, Mono.just("0 Priority"));
        queue.addPublisher(REGULAR, Flux.just("2 Priority", "2 Priority"));
        queue.addPublisher(PERIODIC, Mono.just("1 Priority"));

        StepVerifier
                .create(queue)
                .expectNext("0 Priority")
                .expectNext("1 Priority")
                .expectNext("2 Priority")
                .expectNext("2 Priority")
                .expectNoEvent(Duration.ofMillis(10))
                .then(() -> queue.addPublisher(EMERGENCY, Mono.just("0 Priority")))
                .expectNext("0 Priority")
                .expectNoEvent(Duration.ofMillis(10))
                .thenCancel().verify();
    }

    @Test
    void addInTheMiddle() {
        queue.addPublisher(EMERGENCY, Mono.just("0 Priority"));
        queue.addPublisher(REGULAR, Flux.just("2 Priority", "2 Priority"));
        queue.addPublisher(SECONDARY, Flux.just("3 Priority", "3 Priority"));
        queue.addPublisher(PERIODIC, Mono.just("1 Priority"));

        StepVerifier
                .create(queue, StepVerifierOptions.create().initialRequest(2))
                .expectNext("0 Priority")
                .expectNext("1 Priority")
                .then(() -> queue.addPublisher(EMERGENCY, Mono.just("0 Priority")))
                .thenRequest(2)
                .expectNext("0 Priority")
                .expectNext("2 Priority")
                .then(() -> queue.addPublisher(EMERGENCY, Mono.just("0 Priority")))
                .thenRequest(100)
                .expectNext("0 Priority")
                .expectNext("2 Priority")
                .expectNext("3 Priority")
                .expectNext("3 Priority")
                .then(() -> assertEquals(96, queue.getUnsatisfiedRequestCount()))
                .thenCancel().verify();
    }

    public static class Sub implements Subscriber<String> {

        private Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
            this.subscription = s;
        }

        @Override
        public void onNext(String s) {
            log.info(s);
        }

        @Override
        public void onError(Throwable t) {
            log.error("", t);
        }

        @Override
        public void onComplete() {

        }
    }
}
