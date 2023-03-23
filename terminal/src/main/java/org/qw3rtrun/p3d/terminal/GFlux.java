package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.G;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

public class GFlux {

    private final G g;

    public G getG() {
        return g;
    }

    public Flux<String> getFlux() {
        return flux;
    }

    private final Flux<String> flux;
    private FluxSink<String> sink;

    public GFlux() {
        this.flux = Flux.create(sink -> this.sink = sink);
        this.g = new G(this::onG);
    }

    private void onG(String gcode) {
        sink.next(gcode);
    }
}
