package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Sinks;

import java.util.Arrays;
import java.util.stream.Stream;

class GcodeTerminalApplicationTest {

    @Test
    void test() {
        var sink = Sinks.unsafe().many().multicast().<String>onBackpressureBuffer();
        sink.asFlux().map(String::toUpperCase).subscribe(System.out::print);

        class Sub extends BaseSubscriber<String> {

            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            protected void hookOnNext(String value) {
                System.out.print(value);
                upstream().request(1);
            }
        }
        sink.asFlux().subscribe(new Sub());

        sink.tryEmitNext("Hello");
        sink.tryEmitNext(" ");
        sink.tryEmitNext("World");
        sink.tryEmitNext("!\n");
    }

    @Test
    void streamTest() {
        Stream.of("Test\n", "Hello ", "World!\n")
                .map("|"::concat)
                .forEach(System.out::print);
    }

    @Test
    void fluxTest() {
        Flux.just("Test\n", "Hello ", "World!\n", "Oh\nNo", "!\n")
                .as(BufferedStringFlux::new)
                .asLines()
                .map(s -> "[" + s.trim() + "]")
                .subscribe(System.out::println);
    }
    @Test
    void fluxTest2() {
        Flux.just("Test\n", "Hello ", "World!\n", "Oh\nNo", "!\n")
                .flatMap(s -> Flux.fromStream(s.chars().mapToObj(i -> (char) i).map(Object::toString)))
                .bufferUntil("\n"::equals)
                .map(l -> String.join("", l))
                .map(s -> "[" + s.trim() + "]")
                .subscribe(System.out::println);
    }

    @Test
    void patternTest() {
        System.out.println(Arrays.toString("\n".split("\n")));
    }

    public class BufferedStringFlux extends FluxOperator<String, String> {

        public BufferedStringFlux(Flux<? extends String> source) {
            super(source);
        }

        @Override
        public void subscribe(CoreSubscriber<? super String> actual) {
            source.subscribe(actual);
        }

        public Flux<String> endsWith(String ending) {
            return source
                    .flatMap(s -> Flux.fromStream(s.chars().mapToObj(i -> (char) i).map(Object::toString)))
                    .bufferUntil(ending::equals)
                    .map(l -> String.join("", l));
        }

        public Flux<String> asLines() {
            return endsWith("\n");
        }
    }
}
