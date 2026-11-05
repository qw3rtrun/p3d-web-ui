package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SetAdvancedSettings;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * The planner's own limits - {@code M205 B… S… T… J…}, four unrelated numbers sharing one command
 * (Marlin's arrangement, not this record's):
 *
 * <ul>
 *   <li>{@code minSegmentTimeUs} ({@code B}) - microseconds, the shortest a segment may take. An
 *       <strong>integer</strong>, unlike everything else here, because that is what the firmware
 *       reads.</li>
 *   <li>{@code minFeedrate} ({@code S}) and {@code minTravelFeedrate} ({@code T}) - mm/s floors
 *       below which the planner will not slow a move down.</li>
 *   <li>{@code junctionDeviation} ({@code J}) - millimetres, how far a corner may be rounded.</li>
 * </ul>
 *
 * <p>The generated class's jerk words are deliberately not offered: a build compiled with junction
 * deviation uses {@code J} <em>instead of</em> per-axis jerk, and MarlinSim is such a build - its
 * report has a junction deviation line and no jerk line, so a jerk value sent to it would change
 * nothing while looking like it did.
 *
 * <p>A null value is left alone, and the change is live memory only - see {@link SetStepsPerUnit}.
 */
public record SetAdvancedMotion(Integer minSegmentTimeUs, BigDecimal minFeedrate,
                                BigDecimal minTravelFeedrate, BigDecimal junctionDeviation)
        implements PrinterCommand {

    // M205's first four generated parameters are the jerk words (first is "jerk", writes X) and
    // stay null here; the ones used are b, s, t and the last ("deviation", writes J).
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SetAdvancedSettings(
                null, null, null, null,
                minSegmentTimeUs, minFeedrate, minTravelFeedrate, junctionDeviation));
    }
}
