package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.api.model.MachineProfile;
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

    /** A {@code Cap:} line. A change only when the capability is new or has flipped. */
    public boolean on(CapabilityRs report) {
        var current = profile;
        var previous = current.capabilities().get(report.getCapability());
        if (previous != null && previous == report.getEnabled()) {
            return false;
        }
        var merged = new HashMap<>(current.capabilities());
        merged.put(report.getCapability(), report.getEnabled());
        profile = new MachineProfile(current.firmware(), Map.copyOf(merged), LocalDateTime.now());
        return true;
    }

    /**
     * The profile as the reports that would have announced it: the firmware first, if it is known,
     * then each capability in name order.
     *
     * <p>Name order rather than map order so that two snapshots of the same profile are the same
     * sequence - a page or a test comparing them should not see a reshuffle as a change.
     */
    public List<Object> snapshot() {
        var current = profile;
        var reports = new ArrayList<>();
        if (!current.firmware().isEmpty()) {
            reports.add(new FirmwareInfoRs(current.firmware()));
        }
        new TreeMap<>(current.capabilities())
                .forEach((name, enabled) -> reports.add(new CapabilityRs(name, enabled)));
        return reports;
    }
}
