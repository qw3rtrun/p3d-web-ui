package org.qw3rtrun.p3d.api.control;

import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.reactivestreams.Publisher;

/**
 * Everything happening on every machine, as one stream - plan item D2.
 *
 * <p>One subscription instead of one per printer: a per-printer {@code EventSource}
 * instantiates a runtime as a side effect, so forty machines meant forty streams, sinks and
 * schedulers to render one page.
 *
 * <p>Read-only and separate from {@code PrinterControl}, which acts on one machine rather than
 * watching all of them. The messages are the same {@code EventMessage} the per-printer stream
 * carries, routed by {@code id}; that stream stays, for a monitor page watching one machine.
 */
public interface FarmView {

    /**
     * The farm's events, starting with where every machine is right now - a subscriber sees each
     * machine's current state before anything live arrives, closing the gap a
     * fetch-then-subscribe sequence would leave.
     */
    Publisher<EventMessage> updates();
}
