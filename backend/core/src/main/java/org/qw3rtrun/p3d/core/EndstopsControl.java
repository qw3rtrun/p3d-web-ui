package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.g.marlin.event.EndstopReportHeader;
import org.qw3rtrun.p3d.g.marlin.event.EndstopRs;
import org.qw3rtrun.p3d.g.marlin.event.EndstopStateRs;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of this machine's endstops are triggered, as far as {@code M119}'s answers have said. Same
 * shape as {@link SdCardControl}, {@link TemperatureControl}, {@link ProfileControl} and
 * {@link EEPROMSettingsControl}: {@link #on} folds a reply in, {@link #snapshot()} hands it back as
 * the replies that would have announced it - so a diagnostics page opened after somebody else
 * pressed the button sees the switches rather than an empty table.
 *
 * <p>Simpler than {@link EEPROMSettingsControl} in three ways, each because {@code M119} is a pure
 * read of live hardware: there is no second memory (no stored/live split the way an EEPROM has),
 * nothing gates it (no capability line and no measured refusal, so there is no "unsupported" state
 * to hold), and nothing this host sends invalidates it - only another {@code M119} says anything
 * about a switch.
 *
 * <p>One ordered list rather than a map, because the order is Marlin's own
 * ({@code Endstops::report_states}) and is the only grouping a reader gets; a re-read merges each
 * line in the place it already holds. Which endstops exist, how many, and in what order is the
 * machine's business - measured against MarlinSim (bugfix-2.1.x, Nov 18 2026, {@code localhost:8099},
 * via {@code GCodeLiveProbe}):
 * <pre>
 * &gt;&gt; M119
 * &lt;&lt; Reporting endstop status      EndstopReportHeader
 * &lt;&lt; x_min: open                   EndstopStateRs(x_min, triggered=false)
 * &lt;&lt; y_min: open                   EndstopStateRs(y_min, triggered=false)
 * &lt;&lt; z_min: TRIGGERED              EndstopStateRs(z_min, triggered=true)
 * &lt;&lt; ok P15 B3
 * </pre>
 * Three on that build; a delta, an IDEX or a machine with a filament runout sensor reports a
 * different set, so nothing here assumes one.
 *
 * <h2>Concurrency</h2>
 * One immutable {@link State} behind one volatile reference ({@link SdCardControl}'s pattern): the
 * aggregate's own thread is the only writer (every {@code on} runs on {@code PrinterState.onEvent}'s
 * {@code p3d-printer-<uuid>} thread), any thread may read ({@link #snapshot()} runs on whatever
 * subscribes to {@code PrinterReactor.updates()}), and the volatile write of a fully-built
 * {@code State} is the happens-before edge - a reader sees the whole of the old state or the whole
 * of the new one, never a list mid-rebuild.
 */
public class EndstopsControl {

    /**
     * The endstops as reported, in the order the firmware wrote them.
     *
     * @param states one entry per endstop named so far, each the last line about that name
     */
    record State(List<EndstopStateRs> states) {

        static final State NOTHING = new State(List.of());
    }

    private volatile State state = State.NOTHING;

    State state() {
        return state;
    }

    /** One entry point for the whole family, so {@code PrinterState} has one arm - a reply added to it in {@code :gcode} arrives here already routed. */
    public void on(EndstopRs<?> reply) {
        switch (reply) {
            case EndstopStateRs line -> onState(line);
            case EndstopReportHeader ignored -> {
                // "Reporting endstop status" carries nothing to hold: it is scaffolding ahead of the
                // state lines, not an informative line - the same reading SdCardControl takes of
                // SdFileListBegin. It is still republished by PrinterState, so a page that wants to
                // mark the start of a report can.
            }
            default -> {
                // a member of the family this control keeps nothing about; none exists today, the
                // arm exists because EndstopRs is an interface and not a sealed one.
            }
        }
    }

    /** One endstop, replacing the line held for the same name in its own place, or appended. */
    private void onState(EndstopStateRs line) {
        var states = new ArrayList<>(state.states());
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i).getName().equals(line.getName())) {
                states.set(i, line);
                state = new State(List.copyOf(states));
                return;
            }
        }
        states.add(line);
        state = new State(List.copyOf(states));
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

    /**
     * The endstops as the replies that announced them, in the order first seen - a replacement keeps
     * its place, so the sequence stays the firmware's. {@link EndstopReportHeader} is deliberately
     * not replayed, for the same reason it was never held.
     */
    public List<Object> snapshot() {
        return List.copyOf(state.states());
    }
}
