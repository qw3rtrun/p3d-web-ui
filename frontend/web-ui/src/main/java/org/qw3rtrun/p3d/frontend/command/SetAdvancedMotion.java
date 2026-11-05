package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SetAdvancedSettings;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * The planner's own limits - {@code M205 B… S… T… J…}.
 *
 * <p>Four unrelated numbers sharing one command, which is Marlin's arrangement and not this
 * record's:
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
 * <p><strong>The jerk words the generated class also carries are deliberately not offered.</strong>
 * A build compiled with junction deviation uses {@code J} <em>instead of</em> the per-axis jerk
 * values, and MarlinSim is such a build - its report has a junction deviation line and no jerk
 * line. Sending a jerk value to a machine that is not using them changes nothing while looking
 * exactly like it did, which is the worst available outcome for a calibration control.
 *
 * <p>A null value is left alone, and the change is live memory only - see {@link SetStepsPerUnit}.
 */
public record SetAdvancedMotion(Integer minSegmentTimeUs, BigDecimal minFeedrate,
                                BigDecimal minTravelFeedrate, BigDecimal junctionDeviation)
        implements PrinterCommand {

    /**
     * {@code M205}, positionally.
     *
     * <p>The generated class's first four parameters are the jerk words - the first is called
     * {@code jerk} and writes {@code X} - and all four stay null. The ones used are {@code b},
     * {@code s}, {@code t} and the last, which is called {@code deviation} and writes {@code J}.
     */
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SetAdvancedSettings(
                null, null, null, null,
                minSegmentTimeUs, minFeedrate, minTravelFeedrate, junctionDeviation));
    }
}
