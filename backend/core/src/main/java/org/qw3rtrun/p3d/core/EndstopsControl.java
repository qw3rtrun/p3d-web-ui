package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.g.marlin.event.EndstopReport;
import org.qw3rtrun.p3d.g.marlin.event.EndstopRs;

import java.util.List;

/**
 * Which of this machine's endstops are triggered, as far as {@code M119}'s answers have said. Same
 * shape as {@link SdCardControl}, {@link TemperatureControl}, {@link ProfileControl} and
 * {@link EEPROMSettingsControl}: {@link #on} folds a reply in, {@link #snapshot()} hands it back as
 * the reply that would have announced it - so a diagnostics page opened after somebody else pressed
 * the button sees the switches rather than an empty table.
 *
 * <p><strong>A report replaces the held one wholesale, and that is the difference from
 * {@link EEPROMSettingsControl}.</strong> {@code M119} is a pure read of live hardware that always
 * states every endstop it has - there is no partial {@code M119} - so the newest answer is the whole
 * truth and merging by name would only let a switch that has disappeared from the machine linger.
 * A settings read genuinely is partial (a lone {@code M92} read-back arrives as a one-entry
 * {@code SettingsReport} and must merge into the 25-line dump already held), which is why that
 * control keeps its per-key merge and this one does not.
 *
 * <p>Simpler in three further ways, each because {@code M119} is that pure read: there is no second
 * memory (no stored/live split the way an EEPROM has), nothing gates it (no capability line and no
 * measured refusal, so there is no "unsupported" state to hold), and nothing this host sends
 * invalidates it - only another {@code M119} says anything about a switch.
 *
 * <p>The report's order is Marlin's own ({@code Endstops::report_states}) and is the only grouping a
 * reader gets. Which endstops exist, how many, and in what order is the machine's business -
 * measured against MarlinSim (bugfix-2.1.x, Nov 18 2026, {@code localhost:8099}, via
 * {@code GCodeLiveProbe}): three, {@code x_min} open, {@code y_min} open, {@code z_min} TRIGGERED,
 * in that order, behind a {@code Reporting endstop status} heading that {@code :backend:terminal}
 * now folds away. A delta, an IDEX or a machine with a filament runout sensor reports a different
 * set, so nothing here assumes one.
 *
 * <h2>Concurrency</h2>
 * One immutable {@link State} behind one volatile reference ({@link SdCardControl}'s pattern): the
 * aggregate's own thread is the only writer (every {@code on} runs on {@code PrinterState.onEvent}'s
 * {@code p3d-printer-<uuid>} thread), any thread may read ({@link #snapshot()} runs on whatever
 * subscribes to {@code PrinterReactor.updates()}), and the volatile write of a fully-built
 * {@code State} is the happens-before edge - a reader sees the whole of the old report or the whole
 * of the new one, never one mid-replacement.
 */
public class EndstopsControl {

    /**
     * The last whole {@code M119} answer.
     *
     * @param report every endstop the machine named, in its order, or null before one has arrived
     */
    record State(EndstopReport report) {

        static final State NOTHING = new State(null);
    }

    private volatile State state = State.NOTHING;

    State state() {
        return state;
    }

    /** One entry point for the whole family, so {@code PrinterState} has one arm - a reply added to it in {@code :gcode} arrives here already routed. */
    public void on(EndstopRs<?> reply) {
        if (reply instanceof EndstopReport report) {
            state = new State(report);
        }
        // EndstopReportHeader/EndstopStateRs never reach here individually anymore -
        // :backend:terminal's EndstopReportReader folds every line of one M119 answer into one
        // EndstopReport before PrinterState ever sees it (issue #35). The arm exists because
        // EndstopRs is an interface and not a sealed one.
    }

    /**
     * A new session: an endstop is a switch on a machine that may have been switched off, re-wired
     * or swapped at the same address while the link was down (see
     * {@link SdCardControl#sessionStarted()}), so nothing the last session read is still a fact.
     * Announced to whoever is watching by {@code PrinterState.onOnline}, as a
     * {@code SessionStarted}.
     */
    public void sessionStarted() {
        state = State.NOTHING;
    }

    /** The last report as the reply that announced it, or nothing when no {@code M119} has answered. */
    public List<Object> snapshot() {
        var report = state.report();
        return report == null ? List.of() : List.of(report);
    }
}
