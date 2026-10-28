package org.qw3rtrun.p3d.api.msg;

import java.util.Map;

/**
 * Where the machine says its axes are - the decoded answer to {@code M114}.
 *
 * <p><strong>A map and not four fields</strong>, because which axes exist is the machine's
 * business: a delta reports X/Y/Z, an IDEX reports two carriages, and Marlin builds support for
 * up to nine. Naming them here would mean this record changing shape for a machine type the farm
 * has not met yet, and the UI only ever renders what it was given.
 *
 * <p>Keys are the axis letters Marlin used, upper case - {@code X}, {@code Y}, {@code Z},
 * {@code E}. Values are millimetres.
 *
 * <p><strong>It is the planner's idea of position, not the stepper counts.</strong> Marlin reports
 * both; this carries the first, which is what a move command is expressed in. The two differ while
 * moves are still queued, so a reading taken mid-print is where the machine will be, not where it
 * is.
 */
public record PositionReported(Map<String, Double> axes) implements GEvent {
}
