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

    @Test
    void settingTheHotendReachesTheMachine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("hotend", printer);
        connectAndSettle(id, printer);

        command(id, "/set-temp", "{\"temp\":210}").expectStatus().isOk();

        await("M104 to reach the machine", () -> printer.sent("M104"));
        var sent = printer.firstSent("M104");
        assertTrue(sent.contains("S210"), "the target must survive the trip: " + sent);
    }

    @Test
    void settingTheBedReachesTheMachine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("bed", printer);
        connectAndSettle(id, printer);

        command(id, "/set-bed-temp", "{\"temp\":60}").expectStatus().isOk();

        await("M140 to reach the machine", () -> printer.sent("M140"));
        assertTrue(printer.firstSent("M140").contains("S60"));
    }

    /**
     * The answer arrives <em>on the acknowledgement</em> ({@code ok T:210.00 /210.00 …}), which is
     * why {@code OkTemperatureRs} is a type apart from {@code BareTemperatureRs}: an {@code ok}
     * taken only as a window credit and dropped used to take every on-demand {@code M105} reading
     * with it, with every temperature the host saw coming from {@code M155} instead.
     */
    @Test
    void askingForAReadingPutsTheAnswerOnTheStream() {
        var printer = machine(MockPrinter.marlin());
        var id = register("asked", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/report-temp").expectStatus().isOk();

        await("M105 to reach the machine", () -> printer.sent("M105"));
        var reading = events.await("OkTemperatureRs");
        assertEquals(210.0, degrees(hotend(reading), "current"),
                "the browser should receive the figure the machine stated");
    }

    /**
     * The two classes are one reading, two meanings - answered when asked, against volunteered - and
     * the browser matches them as a family; this is the volunteered one traveling under its own
     * name, the string a TypeScript client switches on.
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
     * #85: the stream used to replay only whatever was said last, so a page opened between two
     * auto-reports drew no temperature until the next one - never, with {@code M155} off. It now
     * opens with what the aggregate holds; the reading here is made <em>before</em> the stream opens
     * and the machine says nothing after, so the figure can only come from the snapshot.
     */
    @Test
    void aPageOpenedLaterIsHandedTheLastReading() {
        var printer = machine(MockPrinter.marlin());
        var id = register("warm", printer);
        connectAndWait(id);

        printer.reportTemperature("198.40", "210.00", "59.80", "60.00");
        await("the aggregate to take the reading", () -> {
            var body = farm.get().uri("/api-printer/{id}", id).exchange()
                    .expectBody(String.class).returnResult().getResponseBody();
            return body != null && body.contains("198.40");
        });

        var late = watch(id);

        var reading = late.await("BareTemperatureRs");
        assertEquals(198.4, degrees(hotend(reading), "current"));
        assertEquals(59.8, degrees(bed(reading), "current"));
        assertEquals("ConnectionStateChanged", late.types().get(0),
                "the connection state leads the snapshot: " + late.types());
    }

    /**
     * {@code GET /api-printer/{id}} answers the current figure once, for a page that renders rather
     * than watches; before anything is reported it answers with <strong>nothing at all</strong>
     * rather than a zero, since a zero reading cannot be told apart from one the firmware really
     * sent.
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
        // The body is the hotend reading itself, not a wrapper around every heater - PrinterControl.state
        // answers "the last hotend reading" shown beside the nozzle; everything else arrives on the stream.
        var reading = json(farm.get().uri("/api-printer/{id}", id).exchange().expectStatus().isOk());
        assertEquals(42.5, degrees(reading, "current"));
        assertEquals(0.0, degrees(reading, "target"));
    }

    // ---------------------------------------------------------------- over several configurations

    /**
     * The machine reported {@code EXTRUDER_COUNT:1} in its {@code M115}, so the aggregate knows the
     * index is out of range and declines to send - the reason {@code handle(SetHotendTemperature)}
     * exists as a named handler rather than a pass-through: it is where the domain rule lands.
     */
    @Test
    void aTargetForASecondToolIsNotSentToASingleExtruderMachine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("one tool", printer);
        // connectAndSettle, not "wait until M155 S1 is on the wire": that only proves the ok closing
        // M115 ran on netty's inbound thread, which writes the next block itself, while FirmwareInfoRs
        // from the same reply is queued onto the aggregate's scheduler and may not have run. The
        // refusal below reads the aggregate's own extruder count.
        connectAndSettle(id, printer);

        command(id, "/set-temp", "{\"index\":1,\"temp\":210}").expectStatus().isOk();

        // Give anything that was going to be written its chance, then assert it was not.
        quiet();
        assertFalse(printer.sent("M104"),
                "this machine has one hotend; a target for the second must not reach it");
    }

    @Test
    void theSameTargetIsSentToADualExtruderMachine() {
        var printer = machine(MockPrinter.dualExtruder());
        var id = register("two tools", printer);
        // Same reason as above, and the direction that would fail for the wrong reason: the default
        // count is 1, so an aggregate that has not yet read EXTRUDER_COUNT:2 refuses the command and
        // no M104 arrives.
        connectAndSettle(id, printer);

        command(id, "/set-temp", "{\"index\":1,\"temp\":210}").expectStatus().isOk();

        await("M104 for the second tool", () -> printer.sent("M104"));
        var sent = printer.firstSent("M104");
        assertTrue(sent.contains("T1"), "the tool index must travel: " + sent);
        assertTrue(sent.contains("S210"), "and so must the target: " + sent);
    }

    /**
     * Named {@code period}, not {@code seconds}: browser and server once disagreed, binding null
     * into every request invisibly, since the endpoint still answered 200.
     */
    @Test
    void theAutoReportPeriodTravels() {
        var printer = machine(MockPrinter.marlin());
        var id = register("periodic", printer);
        connectAndSettle(id, printer);

        command(id, "/auto-report-temp", "{\"period\":3}").expectStatus().isOk();

        await("M155 with the asked-for period", () -> {
            var sent = printer.firstSent("M155");
            return sent != null && sent.contains("S3");
        });
    }

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
     * {@code HeaterReading.current} is a {@code BigDecimal} that reaches the browser as a JSON
     * <em>number</em> ({@code 42.50}, not {@code "42.50"}), so this reads it as a double - the
     * trailing zero is firmware precision, not something comparing the text would preserve.
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

}
