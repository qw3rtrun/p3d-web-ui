package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SetHomeOffsets;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * Where the machine thinks its origin is - {@code M206 X… Y… Z…}, in millimetres.
 *
 * <p>Added to every position after homing, so a {@code Z} of {@code -0.1} puts the nozzle a tenth
 * of a millimetre closer to the bed than the endstop says. That is the one operators reach for, and
 * it is also the one whose sign is easy to get wrong in the direction that drags a nozzle through a
 * glass bed - which is why the page shows the firmware's current value beside the field rather than
 * an empty box.
 *
 * <p>A null axis is left alone, and here that is more than a convenience: an {@code M206} naming
 * one axis must not quietly zero the other two. The change is live memory only - see
 * {@link SetStepsPerUnit}.
 */
public record SetHomeOffset(BigDecimal x, BigDecimal y, BigDecimal z) implements PrinterCommand {

    /**
     * {@code M206} with only the axes that were named.
     *
     * <p><strong>Positional, and this is the one where constructing by name would have done real
     * damage.</strong> {@code SetHomeOffsets}'s first parameter is called {@code offset} and writes
     * <em>{@code P}</em>, not {@code X}; {@code P} is the tool index on a build with per-tool
     * offsets. Writing {@code offset = z} would have put {@code M206 P-0.1} on the wire, which
     * selects a tool rather than lowering a nozzle, and a test written against field names would
     * have agreed with it. {@code x}, {@code y} and {@code z} are the third, fourth and fifth
     * parameters, behind {@code t}.
     */
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SetHomeOffsets(
                null, null,
                x, y, z,
                null, null, null, null, null, null));
    }
}
