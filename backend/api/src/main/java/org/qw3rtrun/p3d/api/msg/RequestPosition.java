package org.qw3rtrun.p3d.api.msg;

/**
 * Ask where the toolhead is - {@code M114}.
 *
 * <p>The answer arrives on the event stream as {@link PositionReported}, not as this call's
 * return: every reply in this application is an event, because a reply can also arrive unasked
 * and a caller that waited for its own would miss those.
 *
 * <p>There is no auto-report for position the way {@code M155} auto-reports temperature, so a
 * page that wants a current reading has to ask. Asking in a loop is how a host floods a printer's
 * command queue with questions while a print is running - the planner is busy and every M114 takes
 * a slot - so a UI should ask on demand and after a move, not on a timer.
 */
public record RequestPosition() implements PrinterCommand {
}
