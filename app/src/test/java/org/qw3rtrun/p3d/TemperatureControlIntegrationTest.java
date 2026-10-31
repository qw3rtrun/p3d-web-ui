package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Heating things, and being told how hot they are - issue #43's {@code TemperatureControl} feature.
 *
 * <p>The round trip has two halves and both are asserted here, because each has shipped broken on
 * its own: a command that reaches 200 and never reaches the wire, and a reply that reaches the host
 * and never reaches the browser. The first half ends at {@link MockPrinter#commands()}, the second
 * at the SSE stream.
 *
 * <p><strong>The configuration that matters for this feature is the extruder count.</strong> A
 * target for tool 1 is a sensible command on a dual-extruder machine and a mistake on a single, and
 * the aggregate is the only place that knows which - so the same request is driven against a
 * machine reporting {@code EXTRUDER_COUNT:1} and one reporting {@code EXTRUDER_COUNT:2}, and the
 * difference is whether anything reaches the wire.
 */
class TemperatureControlIntegrationTest extends FarmIntegrationTest {

    /** Setting the hotend reaches the machine as {@code M104}, carrying the target. */
    @Test
    void settingTheHotendReachesTheMachine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("hotend", printer);
        connectAndWait(id);
        printer.forget();

        command(id, "/set-temp", "{\"temp\":210}").expectStatus().isOk();

        await("M104 to reach the machine", () -> printer.sent("M104"));
        var sent = printer.firstSent("M104");
        assertTrue(sent.contains("S210"), "the target must survive the trip: " + sent);
    }

    /** And the bed as {@code M140}. */
    @Test
    void settingTheBedReachesTheMachine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("bed", printer);
        connectAndWait(id);
        printer.forget();

        command(id, "/set-bed-temp", "{\"temp\":60}").expectStatus().isOk();

        await("M140 to reach the machine", () -> printer.sent("M140"));
        assertTrue(printer.firstSent("M140").contains("S60"));
    }

    /**
     * Asking for a reading sends {@code M105}, and the answer comes back on the stream.
     *
     * <p>The answer arrives <em>on the acknowledgement</em> - {@code ok T:210.00 /210.00 …} - which
     * is why {@code OkTemperatureRs} is a type apart from {@code BareTemperatureRs}. An {@code ok}
     * taken only as a window credit and then dropped takes the answer with it, and that is exactly
     * what used to happen: every temperature the host ever saw came from an {@code M155}
     * auto-report, and an {@code M105} asked on demand was answered into the void.
     */
    @Test
    void askingForAReadingPutsTheAnswerOnTheStream() {
        var printer = machine(MockPrinter.marlin());
        var id = register("asked", printer);
        var events = watch(id);
        connectAndWait(id);
        printer.forget();

        command(id, "/report-temp").expectStatus().isOk();

        await("M105 to reach the machine", () -> printer.sent("M105"));
        var reading = events.await("OkTemperatureRs");
        assertEquals(210.0, degrees(hotend(reading), "current"),
                "the browser should receive the figure the machine stated");
    }

    /**
     * An unsolicited auto-report reaches the browser as a bare reading.
     *
     * <p>The two classes are one reading and two meanings - answered when asked, against volunteered
     * - and the browser has to match them as a family. This asserts the volunteered one travels
     * under its own name, which is the string a TypeScript client switches on.
     */
    @Test
    void anAutoReportReachesTheBrowser() {
        var printer = machine(MockPrinter.marlin());
        var id = register("reporting", printer);
        var events = watch(id);
        connectAndWait(id);

        printer.reportTemperature("198.40", "210.00", "59.80", "60.00");

        var reading = events.await("BareTemperatureRs");
        assertEquals(198.4, degrees(hotend(reading), "current"));
        assertEquals(210.0, degrees(hotend(reading), "target"));
        assertEquals(59.8, degrees(bed(reading), "current"));
    }

    /**
     * The last reading is also answerable without a stream.
     *
     * <p>A page rendering a printer wants the current figure once; a page watching one wants every
     * change. {@code GET /api-printer/{id}} is the first, and before anything has been reported it
     * answers with <strong>nothing at all</strong> rather than a zero - a reading of zero cannot be
     * told apart from one the firmware really sent.
     */
    @Test
    void theLastReadingIsAnsweredWithoutAStream() {
        var printer = machine(MockPrinter.marlin());
        var id = register("queried", printer);
        connectAndWait(id);

        var before = farm.get().uri("/api-printer/{id}", id).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(before == null || before.isBlank(),
                "a machine that has reported no temperature must say so by saying nothing: " + before);

        printer.reportTemperature("42.50", "0.00", "25.00", "0.00");

        await("the aggregate to take the reading", () -> {
            var body = farm.get().uri("/api-printer/{id}", id).exchange()
                    .expectBody(String.class).returnResult().getResponseBody();
            return body != null && body.contains("42.50");
        });
        // The body is the hotend reading itself - current, target, power - and not a wrapper around
        // every heater. `PrinterControl.state` answers "the last hotend reading", which is what a
        // page shows beside the nozzle; everything else a machine reports arrives on the stream.
        var reading = json(farm.get().uri("/api-printer/{id}", id).exchange().expectStatus().isOk());
        assertEquals(42.5, degrees(reading, "current"));
        assertEquals(0.0, degrees(reading, "target"));
    }

    // ---------------------------------------------------------------- over several configurations

    /**
     * A target for tool 1 on a machine with one tool reaches nothing.
     *
     * <p>The machine reported {@code EXTRUDER_COUNT:1} in its {@code M115}, so the aggregate knows
     * the index is out of range and declines to send. That decision is the reason
     * {@code handle(SetHotendTemperature)} exists as a named handler rather than a pass-through -
     * it is where the domain rule lands.
     */
    @Test
    void aTargetForASecondToolIsNotSentToASingleExtruderMachine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("one tool", printer);
        connectAndWait(id);
        await("the firmware report to be taken", () -> printer.sent("M155 S1"));
        printer.forget();

        command(id, "/set-temp", "{\"index\":1,\"temp\":210}").expectStatus().isOk();

        // Give anything that was going to be written its chance, then assert it was not.
        quiet();
        assertFalse(printer.sent("M104"),
                "this machine has one hotend; a target for the second must not reach it");
    }

    /** The identical request, to a machine that reported two tools, does reach the wire. */
    @Test
    void theSameTargetIsSentToADualExtruderMachine() {
        var printer = machine(MockPrinter.dualExtruder());
        var id = register("two tools", printer);
        connectAndWait(id);
        await("the firmware report to be taken", () -> printer.sent("M155 S1"));
        printer.forget();

        command(id, "/set-temp", "{\"index\":1,\"temp\":210}").expectStatus().isOk();

        await("M104 for the second tool", () -> printer.sent("M104"));
        var sent = printer.firstSent("M104");
        assertTrue(sent.contains("T1"), "the tool index must travel: " + sent);
        assertTrue(sent.contains("S210"), "and so must the target: " + sent);
    }

    /**
     * Turning the auto-report on is a command like any other, and the period travels.
     *
     * <p>Named {@code period} and not {@code seconds}: the browser and the server disagreed about
     * that once, which bound null into every request and was invisible because the endpoint still
     * answered 200.
     */
    @Test
    void theAutoReportPeriodTravels() {
        var printer = machine(MockPrinter.marlin());
        var id = register("periodic", printer);
        connectAndWait(id);
        printer.forget();

        command(id, "/auto-report-temp", "{\"period\":3}").expectStatus().isOk();

        await("M155 with the asked-for period", () -> {
            var sent = printer.firstSent("M155");
            return sent != null && sent.contains("S3");
        });
    }

    /** A machine nothing is connected to is not commanded, and the request still answers cleanly. */
    @Test
    void aTargetForAnUnconnectedMachineIsRefusedRatherThanFailing() {
        var printer = machine(MockPrinter.marlin());
        var id = register("not dialled", printer);

        command(id, "/set-temp", "{\"temp\":210}").expectStatus().isOk();

        quiet();
        assertEquals(0, printer.sessions(),
                "a command must not dial a machine nobody asked to connect");
        assertTrue(printer.commands().isEmpty());
    }

    /**
     * One figure off a reading, as a number.
     *
     * <p>{@code HeaterReading.current} is a {@code BigDecimal} and reaches the browser as a JSON
     * <em>number</em> - {@code 42.50}, not {@code "42.50"} - so a client reads it as a double and
     * the trailing zero is the firmware's precision rather than anything the wire preserves.
     * Comparing the text would be asserting on Jackson's rendering of a scale.
     */
    private static double degrees(Map<String, Object> reading, String field) {
        var value = reading.get(field);
        assertNotNull(value, "expected a " + field + " on " + reading);
        return ((Number) value).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hotend(Map<String, Object> reading) {
        return (Map<String, Object>) reading.get("hotend");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bed(Map<String, Object> reading) {
        return (Map<String, Object>) reading.get("bed");
    }

    /** A pause used only where the claim is that nothing was sent. See ConnectivityIntegrationTest. */
    private static void quiet() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
