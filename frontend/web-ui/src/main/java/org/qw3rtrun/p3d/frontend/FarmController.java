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
 * <p>The dashboard opened an {@code EventSource} per printer and each one instantiated a runtime as
 * a side effect; at forty machines that is forty streams, forty sinks and forty single-threaded
 * schedulers to render one page. This is one.
 *
 * <p><strong>The per-printer stream stays.</strong> A monitor page watching one machine should not
 * have to receive the whole farm and filter it, and {@code /api-printer/&#123;id&#125;/stream} is
 * also what seeds that page with the machine's current state. The two are for different pages.
 */
@Slf4j
@RestController
@RequestMapping("/api-farm")
@RequiredArgsConstructor
public class FarmController {

    private final FarmView farm;

    /**
     * Every machine's events, starting with where each of them is now.
     *
     * <p>No scheduling boundary here, unlike the farm's REST endpoints: nothing on this path
     * touches the database. The snapshot is built from what {@code core} already holds in memory
     * and everything after it is a sink.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<EventMessage> stream() {
        return Flux.from(farm.updates());
    }
}
