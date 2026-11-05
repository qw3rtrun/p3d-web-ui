package org.qw3rtrun.p3d.api.model;

/**
 * How much space a machine can print in, in millimetres.
 *
 * <p>Declared rather than discovered: Marlin can report it ({@code M211}, an {@code M503} dump
 * carries the axis limits), but plan item B2 only asked {@code M115}, so nothing knows a bed size
 * unless a human says - firmly the "declared" half of B4's split until something asks {@code M503}.
 *
 * <p>Whole millimetres, since a bed is specified that way and a tenth of a millimetre is not a
 * distinction anybody makes - contrast {@link MachineSpec#nozzleDiameters()}, fractional because
 * 0.4 against 0.6 is the whole point.
 *
 * @param z how high the gantry goes
 */
public record BedVolume(int x, int y, int z) {

    public BedVolume {
        if (x <= 0 || y <= 0 || z <= 0) {
            throw new IllegalArgumentException(
                    "a bed volume is positive in every dimension, not " + x + "x" + y + "x" + z);
        }
    }

    /** Whether a bounding box of this size fits, which is the question plan item D1 will ask. */
    public boolean fits(int width, int depth, int height) {
        return width <= x && depth <= y && height <= z;
    }

    @Override
    public String toString() {
        return x + "x" + y + "x" + z;
    }
}
