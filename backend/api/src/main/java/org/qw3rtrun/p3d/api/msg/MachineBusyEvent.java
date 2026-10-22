package org.qw3rtrun.p3d.api.msg;

/**
 * The machine cannot take commands right now, and says why.
 *
 * <p>One notice, whichever way the firmware spells it: the RepRap protocol writes
 * {@code busy: processing} and Marlin writes the identical thing through its echo channel as
 * {@code echo:busy: processing}. Both are this event by the time they leave the transport, which is
 * what the two spellings being one interface in {@code :gcode} is for.
 */
public interface MachineBusyEvent extends GEvent {

    String reason();
}
