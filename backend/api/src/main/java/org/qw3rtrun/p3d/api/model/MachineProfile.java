package org.qw3rtrun.p3d.api.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * What a machine turned out to be, once something asked it.
 *
 * <p>Firmware key-values exactly as {@code M115} reported them, and the capability flags from the
 * {@code Cap:} lines that follow it - kept as maps rather than named fields because what firmware
 * sends differs between builds, and a host that models only today's keys throws away a later one.
 *
 * <p>Static data, so it belongs to {@code printer-management} ({@code doc/sub_boundaries.txt}):
 * names, connection, types, capabilities and firmware are the roster's business, while what a
 * machine is doing now is {@code core}'s. Only {@code core} can <em>learn</em> this - learning
 * means talking to the printer - so {@code core} reports and {@code management} keeps.
 *
 * @param learnedAt when a machine last told us this; null when nothing ever has
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
     * How many extruders the firmware claims, defaulting to one - not zero, since an unstated
     * machine is far likelier to have a hotend than none, and the caller is usually checking a
     * tool index against this, where zero would reject even tool 0.
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

    /**
     * Whether the firmware said it has an SD card reader - Marlin's {@code Cap:SDCARD}.
     *
     * <p>A question about the <em>firmware</em>, not the slot: a build without SD support reports
     * {@code Cap:SDCARD:0} and will not answer {@code M20}/{@code M21} however many cards are
     * inserted; a build reporting {@code 1} may still have an empty slot. Decides whether the
     * Storage tab is offered; whether a card is present is a separate question only {@code M21}
     * answers.
     *
     * <p>Absent is false - covering both "never reported" and "firmware does not know the
     * capability" (mainline Marlin always writes the line, so in practice it means "never asked");
     * {@link #isKnown()} tells those apart.
     */
    public boolean hasSdCard() {
        return Boolean.TRUE.equals(capabilities.get("SDCARD"));
    }

    /**
     * Whether the firmware said it has an EEPROM it can store settings in - Marlin's
     * {@code Cap:EEPROM}.
     *
     * <p>The capability is {@code EEPROM}; the build macro is {@code EEPROM_SETTINGS} - not the
     * same name. Issue #88's checklist names the macro, but what reaches the wire is
     * {@code Cap:EEPROM:0|1}: measured off MarlinSim ({@code Cap:EEPROM:1} among 38 {@code Cap:}
     * lines) and cross-checked against {@code Marlin/src/gcode/host/M115.cpp} on {@code 2.1.x},
     * where the line is emitted as {@code EEPROM} under {@code ENABLED(EEPROM_SETTINGS)}. A
     * "correction" to the macro name would be false on every machine there is.
     *
     * <p>Like {@link #hasSdCard()}, a question about the <em>firmware</em>: a build without
     * EEPROM support will not answer {@code M500}/{@code M501}/{@code M504} however much flash
     * the board has, so it decides whether the Settings tab is offered. Whether {@code M503}
     * exists is a third question this cannot answer - {@code DISABLE_M503} removes the report
     * from a build that still stores settings - only the machine refusing the command answers
     * that.
     *
     * <p>Absent is false, covering both "never reported" and "firmware does not know the
     * capability"; {@link #isKnown()} tells those apart.
     */
    public boolean hasEeprom() {
        return Boolean.TRUE.equals(capabilities.get("EEPROM"));
    }

    /**
     * Whether the firmware said it can take a file over Marlin's Binary Protocol - Marlin's
     * {@code Cap:BINARY_FILE_TRANSFER}, which decides whether {@code M28 B1} and the packet
     * stream behind it are offered instead of the ASCII write loop.
     *
     * <p>Like {@link #hasSdCard()} and {@link #hasEeprom()}, a question about the
     * <em>firmware</em> rather than about a card being in the slot: a build without
     * {@code BINARY_FILE_TRANSFER} answers {@code M28 B1} as a plain {@code M28} and then reads
     * every packet byte as G-code. Measured value {@code 1} on MarlinSim
     * ({@code Marlin bugfix-2.1.x}, compiled Oct 29 2026), among 40 {@code Cap:} lines.
     *
     * <p>Absent is false, covering both "never reported" and "firmware does not know the
     * capability"; {@link #isKnown()} tells those apart.
     */
    public boolean hasBinaryFileTransfer() {
        return Boolean.TRUE.equals(capabilities.get("BINARY_FILE_TRANSFER"));
    }

    /**
     * Whether the firmware said {@code M28} can create a file under a name longer than DOS 8.3 -
     * Marlin's {@code Cap:LFN_WRITE}, which decides whether an upload may send the name an operator
     * typed or has to generate a short one first.
     *
     * <p><strong>{@code Cap:LONG_FILENAME} is a different capability and is the wrong gate for
     * this.</strong> In {@code Marlin/src/gcode/host/M115.cpp} the first reports
     * {@code LONG_FILENAME_HOST_SUPPORT} - reading and listing long names - and this one reports
     * {@code LONG_FILENAME_WRITE_SUPPORT}, specifically whether a write may open one. They are
     * logically independent, and only this one governs what {@code M28} accepts.
     *
     * <p>Like {@link #hasSdCard()} and {@link #hasBinaryFileTransfer()}, a question about the
     * <em>firmware</em> rather than the card: a build without it answers an over-long
     * {@code M28} with {@code open failed, File: <name>.} and an {@code ok}, writing nothing, while
     * every line meant for the file then runs live on the machine. Measured value {@code 0} -
     * together with {@code Cap:LONG_FILENAME:0} - by {@code LongFileNameProbe} against MarlinSim
     * ({@code Marlin bugfix-2.1.x}, compiled Nov 18 2026) on 2026-11-21.
     *
     * <p>Absent is false, covering both "never reported" and "firmware does not know the
     * capability"; {@link #isKnown()} tells those apart.
     */
    public boolean hasLongFilenameWrite() {
        return Boolean.TRUE.equals(capabilities.get("LFN_WRITE"));
    }

    public String firmwareName() {
        return firmware.get("FIRMWARE_NAME");
    }

    public String machineType() {
        return firmware.get("MACHINE_TYPE");
    }
}
