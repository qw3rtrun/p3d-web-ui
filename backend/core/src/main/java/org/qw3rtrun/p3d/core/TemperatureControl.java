package org.qw3rtrun.p3d.core;


import lombok.Getter;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;

import java.util.Objects;

/**
 * The last temperatures this machine reported, as the firmware stated them.
 *
 * <p><strong>{@code HeaterReading} and not a record of this module's own.</strong> The pair it
 * replaced was a zero-filled copy - an absent target read as {@code 0.0} and an absent power as
 * {@code 0} - which cannot be told from a firmware that really did say zero. A target of zero is
 * "cooling down" and no target at all is "this build does not report one", and a page that draws
 * them the same way is lying about one of them.
 */
@Getter
public class TemperatureControl {

    /** Null until the machine has reported one. */
    private HeaterReading hotend;

    /** Null until the machine has reported one, which some builds never do. */
    private HeaterReading bed;

    /**
     * Takes a report, and says whether it changed anything.
     *
     * <p>A sensor the reply did not mention at all becomes null here, which is the same statement
     * the reply made: no bed reading is not a bed reading of zero.
     */
    public boolean on(TemperatureRs<?> event) {
        var isChanged = !Objects.equals(hotend, event.getHotend())
                || !Objects.equals(bed, event.getBed());
        this.hotend = event.getHotend();
        this.bed = event.getBed();
        return isChanged;
    }

}
