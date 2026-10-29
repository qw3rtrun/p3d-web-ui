package org.qw3rtrun.p3d.api.files;

import java.math.BigDecimal;

/**
 * What the slicer wrote about the print, as far as this host could read it.
 *
 * <p>Every field is nullable, and that is the honest shape. A slicer writes what it feels like in
 * comments it invented, there is no standard for any of it, and a file sliced by something this
 * host has never heard of has none of it. A zero would read as "a print that takes no time and uses
 * no filament", which is a worse answer than "nobody said".
 *
 * @param slicer             the program that produced the file, in its own words
 * @param estimatedSeconds   how long the slicer thinks the print takes
 * @param filamentMillimetres how much filament it thinks the print uses, as length
 * @param layerHeight        the layer height in millimetres
 */
public record SliceInfo(String slicer,
                        Long estimatedSeconds,
                        BigDecimal filamentMillimetres,
                        BigDecimal layerHeight) {

    /** A file nothing could be read from. Not null, so a caller never has to check. */
    public static SliceInfo unknown() {
        return new SliceInfo(null, null, null, null);
    }

    /** Whether anything at all was read out of the file's comments. */
    public boolean isKnown() {
        return slicer != null || estimatedSeconds != null
                || filamentMillimetres != null || layerHeight != null;
    }
}
