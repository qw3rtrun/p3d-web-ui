package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.event.EndstopReportHeader;
import org.qw3rtrun.p3d.g.marlin.event.EndstopRs;
import org.qw3rtrun.p3d.g.marlin.event.EndstopStateRs;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the machine said its endstops are, as the replies that would have announced it.
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

    private static List<EndstopRs<?>> report() {
        return List.of(EndstopReportHeader.INSTANCE, X_OPEN, Y_OPEN, Z_TRIGGERED);
    }

    private final EndstopsControl endstops = new EndstopsControl();

    private void fold(List<EndstopRs<?>> lines) {
        lines.forEach(endstops::on);
    }

    /** Nothing said is nothing known: an unasked machine has no endstops, not open ones. */
    @Test
    void aMachineNobodyAskedHasNoStates() {
        assertEquals(List.of(), endstops.snapshot());
    }

    // Marlin's own order, which is the only thing that says anything about how the axes relate -
    // a map keyed by name would hand a page x_min after z_probe on some builds and before it on
    // others, for no reason a reader could see.
    @Test
    void theWholeReportReplaysInTheOrderItArrived() {
        fold(report());

        assertEquals(List.of(X_OPEN, Y_OPEN, Z_TRIGGERED), endstops.snapshot());
    }

    // what makes a re-read useful: a second M119 answers the same names, so the held report absorbs
    // each line where it already sits. Asserted as the whole sequence, since appending would satisfy
    // "the new value is in there" while showing the axis twice with two different answers.
    @Test
    void aSecondReadingOfOneEndstopReplacesItInPlace() {
        fold(report());
        var moved = new EndstopStateRs("x_min", true);

        endstops.on(moved);

        assertEquals(List.of(moved, Y_OPEN, Z_TRIGGERED), endstops.snapshot());
    }

    // pressing refresh twice must leave the panel as it was, not doubling every row.
    @Test
    void aRereadDoesNotDuplicateAnything() {
        fold(report());
        var once = endstops.snapshot();

        fold(report());

        assertEquals(once, endstops.snapshot());
    }

    /**
     * The heading is held as no information - it is scaffolding ahead of the state lines, the same
     * reading {@code SdCardControl} takes of the file listing's begin/end markers. Both halves are
     * asserted: it is not in the snapshot, and it does not disturb what is.
     */
    @Test
    void theHeaderIsHeldAsNoInformation() {
        fold(report());
        var before = endstops.snapshot();

        endstops.on(EndstopReportHeader.INSTANCE);

        assertEquals(before, endstops.snapshot());
        assertEquals(List.of(X_OPEN, Y_OPEN, Z_TRIGGERED), endstops.snapshot(),
                "a late subscriber is handed states, never the heading that preceded them");
    }

    // an endstop is a switch on a machine that may have been switched off, re-wired or swapped at
    // the same address while the link was down, so last session's answers are not this session's.
    @Test
    void aNewSessionForgetsEverything() {
        fold(report());

        endstops.sessionStarted();

        assertEquals(List.of(), endstops.snapshot());
    }
}
