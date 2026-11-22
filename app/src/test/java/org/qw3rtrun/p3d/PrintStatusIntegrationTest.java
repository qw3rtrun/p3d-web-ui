package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #147's "Print Job status screen" - the SD-card half of it, which is the only print this
 * farm's browser can actually start today: the Storage tab's Print button is {@code M23}+{@code
 * M24} (see {@code PrintFile.java}), with no {@code Job} entity anywhere behind it. {@code
 * JobService}/{@code JobFeeder} track a different, host-streamed kind of print that has no
 * frontend at all yet - deliberately out of scope here, see the PR description.
 *
 * <h2>Why {@code M27} has to be asked more than once</h2>
 * Measured against the live simulator by {@code :backend:terminal:probeHandshake} (2026-11-21):
 * {@code Cap:AUTOREPORT_SD_STATUS:0}, and {@code M27 S1} is accepted but reports nothing ever
 * after. So before this issue, {@code M27} went out exactly once, at connect, via {@code
 * PrinterState.startReportsFor}'s {@code Cap:SDCARD} branch - a print started after that, from
 * either the Storage tab or the printer's own screen, was invisible until something asked again.
 * {@code SdStatusPoll} (new, in {@code :backend:core}) is that something; these tests are the
 * proof it actually asks, not a reading of its source.
 *
 * <h2>No {@code Job}, and that is the point being tested</h2>
 * Every assertion here is against {@code SdPrinting}/{@code SdNotPrinting} on the event stream,
 * {@code M27}'s own decoded reply - never against {@code /api-jobs}, which this feature does not
 * touch.
 *
 * <h2>The same context as everything else, on purpose</h2>
 * A forked context was tried and abandoned: {@code @SpringBootTest(properties = …)} on this class
 * alone, even pointed at its own database, left a second context cached alongside the shared one
 * for the rest of the suite's run - and measured, twice, with a different handful of unrelated
 * tests failing each time (a 500 standing up {@code Events}, a roster read coming back null) -
 * something in this assembly is shared more widely than per-context, and two live contexts find
 * it. {@code application-test.properties}'s own comment already named the cost of a fork as "a
 * second boot of the whole farm"; it is in fact worse than that, and not this feature's to debug.
 * {@code SdStatusPoll} runs in the one shared context at the production interval
 * ({@code p3d.printer.sd-status-poll-interval} is not overridden here), and the handful of
 * pre-existing tests a stray tick could have reached were made to tolerate one instead - see
 * {@code PrintJobIntegrationTest.aFeedingJobIsPreemptedByAnInteractiveCommandAndResumesWhereItWas}
 * and {@code TerminalIntegrationTest.theAggregatesOwnJudgementIsNotAppliedToATypedLine}.
 */
class PrintStatusIntegrationTest extends FarmIntegrationTest {

    /**
     * Proves the poll exists at all, independent of whether anything is printing: {@code M27}
     * answers {@code Not SD printing} either way, so a count of how many times it was <em>asked</em>
     * is the only honest observable - counting {@code SdNotPrinting} events would be
     * indistinguishable from the one the handshake already sends.
     */
    @Test
    void sdStatusIsPolledRepeatedlyWhileConnected() {
        var printer = machine(MockPrinter.marlin());
        var id = register("polled", printer);
        connectAndSettle(id, printer);

        await(() -> "at least three M27s after the handshake's own one. Received "
                        + printer.commands(),
                () -> printer.commands().stream().filter("M27"::equals).count() >= 3);
    }

    /** The control: a machine with no card reader must never be asked - there is nothing to ask. */
    @Test
    void sdStatusIsNotPolledWithoutACard() {
        var printer = machine(MockPrinter.withoutCard());
        var id = register("no card, not polled", printer);
        connectAndSettle(id, printer);

        quiet();
        assertFalse(printer.sent("M27"),
                "a machine with no card reader has nothing for M27 to ask: " + printer.commands());
    }

    /**
     * A print started from the Storage tab's Print button, watched from the Monitor tab's own
     * stream: position advances across successive polls with no browser action in between, which
     * is the whole claim of the poll existing - a static {@code SdPrinting} once would prove only
     * that {@code M24} itself was answered, which the handshake already covered for a plain M27.
     */
    @Test
    void aRunningPrintsPositionAdvancesAcrossSuccessivePolls() {
        var printer = machine(MockPrinter.marlin()).withFile("BENCHY.GCO", 1000);
        var id = register("printing, watched passively", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/sd/print", "{\"name\":\"BENCHY.GCO\"}").expectStatus().isOk();
        await("the print to start", () -> printer.sent("M24"));

        await(() -> "at least two distinct SdPrinting readings. Saw "
                        + events.allOf("SdPrinting"),
                () -> events.allOf("SdPrinting").size() >= 2);

        var readings = events.allOf("SdPrinting");
        var first = number(readings.get(0).get("position"));
        var second = number(readings.get(1).get("position"));
        assertTrue(second > first,
                "a later poll must read further into the file than an earlier one: " + readings);
        assertEquals(1000, number(readings.get(0).get("size")),
                "the denominator is the file's own size, the same number SdFileOpened named");
    }

    /** {@code M25}, the firmware's own pause - held by the card reader, not by this host. */
    @Test
    void pausingAPrintSendsM25() {
        var printer = machine(MockPrinter.marlin()).withFile("BENCHY.GCO", 1000);
        var id = register("pausing", printer);
        connectAndSettle(id, printer);

        command(id, "/sd/print", "{\"name\":\"BENCHY.GCO\"}").expectStatus().isOk();
        await("the print to start", () -> printer.sent("M24"));
        printer.forget();

        command(id, "/sd/pause").expectStatus().isOk();

        await("M25 to reach the machine", () -> printer.sent("M25"));
    }

    /** {@code M24} again, which {@code PrintFile}'s own javadoc already established is a resume. */
    @Test
    void resumingAPausedPrintSendsM24Again() {
        var printer = machine(MockPrinter.marlin()).withFile("BENCHY.GCO", 1000);
        var id = register("resuming", printer);
        connectAndSettle(id, printer);

        command(id, "/sd/print", "{\"name\":\"BENCHY.GCO\"}").expectStatus().isOk();
        await("the print to start", () -> printer.sent("M24"));
        command(id, "/sd/pause").expectStatus().isOk();
        await("M25 to reach the machine", () -> printer.sent("M25"));
        printer.forget();

        command(id, "/sd/resume").expectStatus().isOk();

        await("M24 to reach the machine again", () -> printer.sent("M24"));
    }

    /**
     * {@code M524}, and the machine's own {@code M27} agrees afterwards - the round trip, not just
     * the command, since a stop that only reached the wire and never changed what the browser is
     * told would be exactly the kind of feature this repository has shipped dead before.
     */
    @Test
    void stoppingAPrintSendsM524AndTheBrowserIsToldItStopped() {
        var printer = machine(MockPrinter.marlin()).withFile("BENCHY.GCO", 1000);
        var id = register("stopping", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/sd/print", "{\"name\":\"BENCHY.GCO\"}").expectStatus().isOk();
        await("the print to start", () -> printer.sent("M24"));
        events.await("SdPrinting");
        printer.forget();

        command(id, "/sd/stop").expectStatus().isOk();

        await("M524 to reach the machine", () -> printer.sent("M524"));
        await(() -> "SdNotPrinting once the next poll runs after the abort. Saw "
                        + events.allOf("SdPrinting").size() + " SdPrinting reading(s) and "
                        + (events.saw("SdNotPrinting") ? "" : "no ") + "SdNotPrinting",
                () -> events.saw("SdNotPrinting"));
    }

    /**
     * A dashboard opened while a print is already running must not read "unknown" until the next
     * poll happens to land - the whole point of {@code FarmEvents.currentStateOf} picking up
     * {@code SdStatusRs<?>}, proved the same way the connection/temperature catch-up already is in
     * {@code FarmDashboardIntegrationTest}.
     */
    @Test
    void aDashboardOpenedMidPrintCatchesUpOnTheSdStatusImmediately() {
        var printer = machine(MockPrinter.marlin()).withFile("BENCHY.GCO", 1000);
        var id = register("caught up on", printer);
        var perPrinter = connectAndSettle(id, printer);

        command(id, "/sd/print", "{\"name\":\"BENCHY.GCO\"}").expectStatus().isOk();
        perPrinter.await("SdPrinting");

        var dashboard = watchTheFarm();

        dashboard.await(id, "SdPrinting");
    }
}
