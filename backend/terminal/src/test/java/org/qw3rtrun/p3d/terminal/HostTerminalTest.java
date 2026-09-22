package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.terminal.msg.Replay;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostTerminalTest {

    private final Sinks.Many<String> wire = Sinks.many().multicast().onBackpressureBuffer();
    private final List<String> published = new ArrayList<>();
    private final HostTerminal terminal = new HostTerminal(wire::asFlux, out -> Flux.from(out).then());

    private void listen() {
        terminal.messageFlux().map(Replay.Message::raw).subscribe(published::add);
        terminal.start(Flux.never()).subscribe();
    }

    private void receive(String line) {
        wire.tryEmitNext(line + "\n");
    }

    /**
     * {@code M105}, {@code M109} and {@code M190} answer on the {@code ok}. Taking that line only
     * as a window credit drops the answer, which is what happened before: an {@code M105} asked on
     * demand was never seen by anything downstream.
     */
    @Test
    void anOkThatCarriesAReplyIsPublishedWholeLine() {
        listen();

        receive("ok T:45.26 /45.00 B:25.00 /0.00 @:3 B@:0");

        assertEquals(List.of("ok T:45.26 /45.00 B:25.00 /0.00 @:3 B@:0"), published);
    }

    @Test
    void aBareOkCarriesNothingToPublish() {
        listen();

        receive("ok");

        assertEquals(List.of(), published);
    }

    /** An advanced {@code ok} says only what the send window was already told. */
    @Test
    void anAdvancedOkIsWindowCreditAndNotAReply() {
        listen();

        receive("ok P15 B3 N100");

        assertEquals(List.of(), published);
    }

    @Test
    void anUnprefixedLineIsStillPublished() {
        listen();

        receive("T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0");

        assertEquals(List.of("T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0"), published);
    }
}
