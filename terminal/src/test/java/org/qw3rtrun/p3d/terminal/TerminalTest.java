package org.qw3rtrun.p3d.terminal;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class TerminalTest {
    @Test
    void test() {
        var publisher = Mono.just("Hello World").log();
        publisher
                .subscribeOn(Schedulers.newSingle("publisher Thread"))
                .subscribe(new CoreSubscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                log.info("onSubscribe {}", s);
                s.request(1);
            }

            @Override
            public void onNext(String s) {
                log.info("onNext {}", s);
            }

            @Override
            public void onError(Throwable t) {
                log.error("onError", t);
            }

            @Override
            public void onComplete() {
                log.info("onComplete");
            }
        });
        log.info("Done");
    }
}
