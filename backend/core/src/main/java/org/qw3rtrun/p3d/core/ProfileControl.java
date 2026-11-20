package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.g.marlin.event.CapabilitiesReport;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What this machine is, as far as anyone knows: its firmware report and its capabilities.
 *
 * <p>Seeded from the roster rather than discovered from nothing - it used to be two objects
 * (nullable firmware, an empty capability map) starting empty every process, so the extruder-count
 * check in {@link PrinterState} had nothing to read until a machine was dialled and a command to one
 * that had not NPE'd instead of being refused. {@code printer-management} persists and announces
 * this value, so a restart knows what each machine is before anything connects to it; replaced
 * wholesale on each change (volatile, since a snapshot reads it from whichever thread builds one).
 *
 * <p>Same shape as {@link TemperatureControl}: each {@code on} takes a report and says whether it
 * changed anything, so the aggregate announces only what is news, and {@link #snapshot()} hands the
 * current value back as the reports that would have announced it.
 */
public class ProfileControl {

    private volatile MachineProfile profile;

    public ProfileControl(MachineProfile seed) {
        this.profile = seed == null ? MachineProfile.unknown() : seed;
    }

    /** Never null. */
    public MachineProfile get() {
        return profile;
    }

    /**
     * An {@code M115} report. A change only when the firmware's own fields differ: the same report
     * on every redial is not news, and announcing it would write to the database every connect.
     */
    public boolean on(FirmwareInfoRs report) {
        var current = profile;
        if (report.getKeyValues().equals(current.firmware())) {
            return false;
        }
        profile = new MachineProfile(report.getKeyValues(), current.capabilities(), LocalDateTime.now());
        return true;
    }

    /**
     * One whole {@code M115} answer's {@code Cap:} lines. A change only when at least one entry in
     * the batch is new or has flipped - the same "a change only when the firmware's own fields
     * differ" reading {@link #on(FirmwareInfoRs)} takes, now evaluated over the whole answer rather
     * than line by line (#144). A reconnect reporting exactly what the roster already held is
     * therefore not news, and nothing is announced or written to the database for it.
     */
    public boolean on(CapabilitiesReport report) {
        var current = profile;
        var merged = new HashMap<>(current.capabilities());
        var changed = false;
        for (CapabilityRs cap : report.getCapabilities()) {
            var previous = merged.put(cap.getCapability(), cap.getEnabled());
            if (previous == null || previous != cap.getEnabled()) {
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        profile = new MachineProfile(current.firmware(), Map.copyOf(merged), LocalDateTime.now());
        return true;
    }

    /**
     * The profile as the reports that would have announced it: the firmware first, if it is known,
     * then every capability as one {@link CapabilitiesReport}, in name order.
     *
     * <p>One folded report rather than one {@link CapabilityRs} per capability (#144), so a late
     * subscriber's replay has the same shape as what a live stream now carries - the same reading
     * {@code EndstopsControl.snapshot()} and {@code EEPROMSettingsControl.snapshot()} take of their
     * own folded types after #35. A machine with no capabilities carries nothing rather than an
     * empty batch: absent is not the same as "it reported none".
     *
     * <p>Name order rather than map order so that two snapshots of the same profile are the same
     * sequence - a page or a test comparing them should not see a reshuffle as a change. It is not
     * the firmware's own arrival order, and cannot be: the persisted capability map records no
     * order at all, so name order is the only stable one a replay can offer.
     */
    public List<Object> snapshot() {
        var current = profile;
        var reports = new ArrayList<>();
        if (!current.firmware().isEmpty()) {
            reports.add(new FirmwareInfoRs(current.firmware()));
        }
        if (!current.capabilities().isEmpty()) {
            var ordered = new TreeMap<>(current.capabilities());
            var capabilities = new ArrayList<CapabilityRs>(ordered.size());
            ordered.forEach((name, enabled) -> capabilities.add(new CapabilityRs(name, enabled)));
            reports.add(new CapabilitiesReport(capabilities));
        }
        return reports;
    }
}
