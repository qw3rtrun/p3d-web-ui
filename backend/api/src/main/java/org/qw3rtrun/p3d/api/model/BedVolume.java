package org.qw3rtrun.p3d.api.model;

/**
 * How much space a machine can print in, in millimetres.
 *
 * <p>Declared rather than discovered. Marlin will report it - {@code M211} gives the software
 * endstops and an {@code M503} dump carries the axis limits - but plan item B2 only asked
 * {@code M115}, so nothing in the farm knows a bed size unless a human says. That is the division
 * plan item B4 draws: discovered where possible, declared where not, and this is firmly the second
 * until something asks {@code M503}.
 *
 * <p>Whole millimetres, because a bed is specified in them and a tenth of a millimetre of build
 * volume is not a distinction anybody makes. Contrast {@link MachineSpec#nozzleDiameters()}, which
 * is fractional because 0.4 against 0.6 is the whole point of it.
 *
 * @param x the bed's width
 * @param y its depth
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
