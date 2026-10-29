package org.qw3rtrun.p3d.api.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * What a machine turned out to be, once something asked it.
 *
 * <p>Firmware key-values exactly as {@code M115} reported them, and the capability flags from the
 * {@code Cap:} lines that follow it. Kept as maps rather than as named fields because that is what
 * the firmware sends and what it sends differs between builds: a host that models only the keys it
 * knows today throws away the one a later Marlin adds.
 *
 * <p><strong>Static data, so it belongs to {@code printer-management}.</strong> That is
 * {@code doc/sub_boundaries.txt} read literally - names, connection information, types,
 * capabilities and firmware versions are the roster's business, while what a machine is doing right
 * now is {@code core}'s. The awkward part is that only {@code core} can <em>learn</em> this, because
 * learning it means talking to the printer; so {@code core} reports and {@code management} keeps.
 *
 * @param firmware     every key {@code M115} reported, in the firmware's own words
 * @param capabilities each {@code Cap:} line, by name
 * @param learnedAt    when a machine last told us this. Null when nothing ever has
 */
public record MachineProfile(Map<String, String> firmware,
                             Map<String, Boolean> capabilities,
                             LocalDateTime learnedAt) {

    /** A machine nothing has ever connected to. Not null, so a caller never has to check. */
    public static MachineProfile unknown() {
        return new MachineProfile(Map.of(), Map.of(), null);
    }

    /** Whether anything was ever learned. False for a machine that has never been reached. */
    public boolean isKnown() {
        return learnedAt != null;
    }

    /**
     * How many extruders the firmware claims, defaulting to one.
     *
     * <p>One, and not zero, because a machine that did not say is far likelier to have a hotend
     * than none - and because the caller is usually about to check a tool index against this, where
     * zero rejects every command including the one for tool 0.
     */
    public int extruderCount() {
        var raw = firmware.get("EXTRUDER_COUNT");
        if (raw == null) {
            return 1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    public String firmwareName() {
        return firmware.get("FIRMWARE_NAME");
    }

    public String machineType() {
        return firmware.get("MACHINE_TYPE");
    }
}
