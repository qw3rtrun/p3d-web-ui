package org.qw3rtrun.p3d.terminal;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

public class GFlux {

    private final GSender g;

    public GSender getG() {
        return g;
    }

    public Flux<String> getFlux() {
        return flux;
    }

    private final Flux<String> flux;
    private FluxSink<String> sink;

    public GFlux() {
        this.flux = Flux.create(sink -> this.sink = sink);
        this.g = new GSender(this::onG);
    }

    private void onG(String gcode) {
        sink.next(gcode);
    }
}
