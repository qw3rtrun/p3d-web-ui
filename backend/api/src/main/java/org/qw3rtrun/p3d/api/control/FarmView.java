package org.qw3rtrun.p3d.api.control;

import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.reactivestreams.Publisher;

/**
 * Everything happening on every machine, as one stream - plan item D2.
 *
 * <p><strong>One subscription instead of N.</strong> The dashboard opened an {@code EventSource}
 * per printer, and each one instantiated a runtime as a side effect: at forty machines that is
 * forty streams, forty sinks and forty single-threaded schedulers to render one page.
 *
 * <p><strong>Read-only, which is why it is not on {@code PrinterControl}.</strong> That interface
 * is how a caller acts on one machine; this is how a caller watches all of them, and a farm view
 * that could also send commands would be a different and larger thing.
 *
 * <p>The messages are the same {@code EventMessage} the per-printer stream carries, so a browser
 * routes by {@code id} and reads the payload exactly as it already does. The per-printer stream
 * stays: a monitor page watching one machine should not have to filter the whole farm.
 */
public interface FarmView {

    /**
     * The farm's events, starting with where every machine is right now.
     *
     * <p><strong>A subscriber is told the current state of each machine before anything live
     * arrives</strong>, which is what makes "one subscription" enough to draw the page. The
     * alternative - fetch the list, then subscribe - has a gap between the two where an event is
     * missed, and the page then shows a stale row until the machine happens to say something else.
     */
    Publisher<EventMessage> updates();
}
