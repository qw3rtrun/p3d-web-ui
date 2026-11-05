package org.qw3rtrun.p3d.core;


import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;

import java.util.Objects;
import java.util.Optional;

/**
 * The last temperature report this machine made, as the firmware stated it.
 *
 * <p>{@code HeaterReading}, not a record of this module's own: the pair it replaced zero-filled an
 * absent target/power, indistinguishable from a firmware that really said zero - "cooling down" vs
 * "this build does not report one". The whole report is kept, not two of its sensors, so it can be
 * handed to a late subscriber as the event that announced it - see {@link PrinterState#snapshot()}.
 * Volatile: written on the aggregate's thread, read from whichever thread builds a snapshot.
 */
public class TemperatureControl {

    /** Null until the machine has reported a temperature. */
    private volatile TemperatureRs<?> current;

    /**
     * Takes a report, and says whether it changed anything.
     *
     * <p><strong>A change is a change of reading</strong> - any sensor, its current, target or
     * power - and not a change of class. The same figures arriving on an {@code ok} after an
     * auto-report are not news, and comparing the reports themselves would say they were, because
     * {@code OkTemperatureRs} and {@code BareTemperatureRs} are never equal.
     *
     * <p>A sensor the reply did not mention at all becomes null here, which is the same statement
     * the reply made: no bed reading is not a bed reading of zero.
     */
    public boolean on(TemperatureRs<?> event) {
        var previous = current;
        current = event;
        return previous == null || !sameReadings(previous, event);
    }

    /** The last report, or nothing when the machine has not made one. */
    public Optional<TemperatureRs<?>> current() {
        return Optional.ofNullable(current);
    }

    /** The active hotend's last reading, or null when there is none. */
    public HeaterReading getHotend() {
        var last = current;
        return last == null ? null : last.getHotend();
    }

    /** The bed's last reading, or null when there is none - which some builds never send. */
    public HeaterReading getBed() {
        var last = current;
        return last == null ? null : last.getBed();
    }

    /** Every sensor the two reports carry, compared figure by figure. */
    static boolean sameReadings(TemperatureRs<?> a, TemperatureRs<?> b) {
        return Objects.equals(a.getHotend(), b.getHotend())
                && Objects.equals(a.getBed(), b.getBed())
                && Objects.equals(a.getChamber(), b.getChamber())
                && Objects.equals(a.getProbe(), b.getProbe())
                && Objects.equals(a.getCooler(), b.getCooler())
                && Objects.equals(a.getBoard(), b.getBoard())
                && Objects.equals(a.getRedundant(), b.getRedundant())
                && Objects.equals(a.getHotends(), b.getHotends());
    }
}
