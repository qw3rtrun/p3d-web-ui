package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.event.EndstopReport;
import org.qw3rtrun.p3d.g.marlin.event.EndstopStateRs;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the machine said its endstops are, as the reply that would have announced it.
 *
 * <p>Issue #35's redo: {@code :backend:terminal}'s {@code EndstopReportReader} now folds one
 * {@code M119} answer's several lines into one {@code EndstopReport} before this control ever sees
 * it, so {@link EndstopsControl#on} takes exactly one value per read rather than one call per line -
 * every fixture below is built that way.
 */
class EndstopsControlTest {

    // M119's answer as MarlinSim writes it, captured by GCodeLiveProbe on 2026-11-18 against
    // bugfix-2.1.x on localhost:8099:
    //
    //   >> M119
    //   << Reporting endstop status
    //   << x_min: open
    //   << y_min: open
    //   << z_min: TRIGGERED
    //   << ok P15 B3
    //
    // Three endstops on this build; a real machine reports whatever Endstops::report_states
    // iterates, in that order, so nothing here assumes a set or a count.
    private static final EndstopStateRs X_OPEN = new EndstopStateRs("x_min", false);
    private static final EndstopStateRs Y_OPEN = new EndstopStateRs("y_min", false);
    private static final EndstopStateRs Z_TRIGGERED = new EndstopStateRs("z_min", true);

    private static final EndstopReport REPORT = new EndstopReport(List.of(X_OPEN, Y_OPEN, Z_TRIGGERED));

    private final EndstopsControl endstops = new EndstopsControl();

    /** Nothing said is nothing known: an unasked machine has no report, not an empty one. */
    @Test
    void aMachineNobodyAskedHasNoReport() {
        assertEquals(List.of(), endstops.snapshot());
    }

    // Marlin's own order, which is the only thing that says anything about how the axes relate -
    // a map keyed by name would hand a page x_min after z_probe on some builds and before it on
    // others, for no reason a reader could see.
    @Test
    void theWholeReportReplaysAsOneValue() {
        endstops.on(REPORT);

        assertEquals(List.of(REPORT), endstops.snapshot());
    }

    /**
     * A second {@code M119} answer replaces the held one <strong>wholesale</strong>, not merged by
     * name - the difference from {@code EEPROMSettingsControl}'s per-key merge, and why: {@code M119}
     * always states every endstop it has, so there is no partial answer for a merge to make sense
     * of, and a switch that disappeared from the newer report would otherwise linger forever.
     */
    @Test
    void aSecondReportReplacesTheFirstWholesale() {
        endstops.on(REPORT);
        var moved = new EndstopReport(List.of(new EndstopStateRs("x_min", true), Y_OPEN));

        endstops.on(moved);

        assertEquals(List.of(moved), endstops.snapshot(),
                "the second report entirely, not a merge of the two: " + endstops.snapshot());
    }

    // pressing refresh twice must leave the panel as it was, not doubling every row.
    @Test
    void aRereadDoesNotDuplicateAnything() {
        endstops.on(REPORT);
        var once = endstops.snapshot();

        endstops.on(REPORT);

        assertEquals(once, endstops.snapshot());
    }

    // an endstop is a switch on a machine that may have been switched off, re-wired or swapped at
    // the same address while the link was down, so last session's answer is not this session's.
    @Test
    void aNewSessionForgetsEverything() {
        endstops.on(REPORT);

        endstops.sessionStarted();

        assertEquals(List.of(), endstops.snapshot());
    }
}
