package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SetHomeOffsets;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * Where the machine thinks its origin is - {@code M206 X… Y… Z…}, in millimetres.
 *
 * <p>Added to every position after homing, so a {@code Z} of {@code -0.1} puts the nozzle a tenth
 * of a millimetre closer to the bed than the endstop says - the sign is easy to get wrong in the
 * direction that drags a nozzle through a glass bed, which is why the page shows the firmware's
 * current value beside the field rather than an empty box.
 *
 * <p>A null axis is left alone - an {@code M206} naming one axis must not quietly zero the other
 * two. Live memory only - see {@link SetStepsPerUnit}.
 */
public record SetHomeOffset(BigDecimal x, BigDecimal y, BigDecimal z) implements PrinterCommand {

    // Positional, and real damage would follow from constructing by name: SetHomeOffsets's first
    // parameter is called "offset" and writes P, the tool index on a per-tool-offset build, not X.
    // x/y/z are the third, fourth and fifth parameters, behind t.
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SetHomeOffsets(
                null, null,
                x, y, z,
                null, null, null, null, null, null));
    }
}
