package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.control.FarmView;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * One stream for the whole farm - plan item D2.
 *
 * <p>The dashboard used to open an {@code EventSource} per printer, each instantiating a runtime as
 * a side effect - forty machines meant forty streams, forty sinks and forty schedulers to render one
 * page. This is one.
 *
 * <p>The per-printer {@code /api-printer/&#123;id&#125;/stream} stays, for the monitor page watching
 * one machine: it should not have to receive the whole farm and filter it, and that stream is also
 * what seeds the page with the machine's current state.
 */
@Slf4j
@RestController
@RequestMapping("/api-farm")
@RequiredArgsConstructor
public class FarmController {

    private final FarmView farm;

    // No scheduling boundary here, unlike the farm's REST endpoints: nothing on this path touches
    // the database - the snapshot comes from what core already holds in memory.
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<EventMessage> stream() {
        return Flux.from(farm.updates());
    }
}
