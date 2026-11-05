package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the farm is made of - issue #43's {@code PrinterManagement} feature. Replaces
 * {@code PrinterProfileFlowTest} and {@code MachineSpecFlowTest}, which asserted the same facts by
 * autowiring the roster and publishing {@code MFirmwareReportedEvent} by hand; two things are
 * better for the move: the firmware report is real (a dialled machine's own {@code M115}, where
 * the old test could not have caught one that was never sent), and the override is asserted where
 * it bites - a command for tool 1 reaching the wire on a machine whose firmware reports one
 * extruder.
 *
 * <p>It also holds the roster's half of issue #65: asking for a machine the farm does not have is a
 * <strong>404</strong>, not 500 - a status with no second observable, since there is no machine to
 * have been touched.
 *
 * <p>Not carried over: the assertion that {@code announceTheFarm()} re-announces a spec at
 * startup. It has no endpoint and a test here cannot restart a context; it stays covered in
 * {@code :backend:core}.
 */
class PrinterManagementIntegrationTest extends FarmIntegrationTest {

    private static final String DECLARED = """
            {"bedVolume":{"x":220,"y":220,"z":250},"extruderCount":2,
             "nozzleDiameters":[0.4,0.6],"materials":["PETG"],"tags":["prusa-shelf"]}""";

    @Test
    void aMachineIsRegisteredAndReadableAfterwards() {
        var printer = machine(MockPrinter.marlin());
        var id = register("the new one", printer);

        var described = describe(id);
        assertEquals("the new one", described.get("name"));
        assertEquals("localhost", ((Map<?, ?>) described.get("connection")).get("host"));
        assertEquals(printer.port(), ((Map<?, ?>) described.get("connection")).get("port"));
        assertEquals("OFFLINE", described.get("state"));

        var roster = farm.get().uri("/api-manager").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(roster.contains(id.toString()), "the roster should list what was just added");
    }

    /** Registering needs no spec: plugging a machine in should not require knowing its bed size. */
    @Test
    void aMachineCanBeRegisteredWithoutAnybodyDescribingIt() {
        var printer = machine(MockPrinter.marlin());
        var id = register("undescribed", printer);

        assertNull(spec(describe(id)).get("extruderCount"),
                "nobody has said anything about it, and that is a third state, not a zero");
        assertNull(spec(describe(id)).get("bedVolume"));
    }

    /**
     * Only {@code core} can learn this (it means talking to the printer); only the roster can keep
     * it. The fact crosses the boundary twice - out as {@code MFirmwareReportedEvent}, back inside
     * the announcement - and this is the far side of that round trip, read through the endpoint a
     * page uses.
     */
    @Test
    void whatTheMachineReportsIsKeptByTheRoster() {
        var printer = machine(MockPrinter.marlin());
        var id = register("tells us what it is", printer);

        var events = connectAndLearn(id);

        awaitRoster(id, events, () -> firmware(describe(id)).containsKey("FIRMWARE_NAME"),
                "the roster to learn the firmware");
        var firmware = firmware(describe(id));
        assertTrue(((String) firmware.get("FIRMWARE_NAME")).startsWith("Marlin bugfix-2.1.x"),
                "the firmware name should be the machine's own words: " + firmware);
        assertEquals("1", firmware.get("EXTRUDER_COUNT"));
        assertEquals("MarlinSim", firmware.get("MACHINE_TYPE"));

        awaitRoster(id, events, () -> capabilities(describe(id)).containsKey("AUTOREPORT_TEMP"),
                "the capabilities to arrive");
        assertEquals(Boolean.TRUE, capabilities(describe(id)).get("AUTOREPORT_TEMP"));
        assertEquals(Boolean.TRUE, capabilities(describe(id)).get("SDCARD"));
    }

    @Test
    void aMachineWithoutACardReaderSaysSo() {
        var printer = machine(MockPrinter.withoutCard());
        var id = register("no reader", printer);

        var events = connectAndLearn(id);

        awaitRoster(id, events, () -> capabilities(describe(id)).containsKey("SDCARD"),
                "the capabilities to arrive");
        assertEquals(Boolean.FALSE, capabilities(describe(id)).get("SDCARD"),
                "the page gates its Storage tab on this");
    }

    @Test
    void theDeclaredHalfAndTheReportedHalfAreBothKept() {
        var printer = machine(MockPrinter.marlin());
        var id = register("described", printer);
        var events = connectAndLearn(id);
        awaitRoster(id, events, () -> firmware(describe(id)).containsKey("EXTRUDER_COUNT"),
                "the roster to learn the firmware");

        update(id, "described", printer.port(), DECLARED);

        var described = describe(id);
        assertEquals(2, spec(described).get("extruderCount"), "what a person typed");
        assertEquals("1", firmware(described).get("EXTRUDER_COUNT"), "and what the machine said");
        assertEquals(Map.of("x", 220, "y", 220, "z", 250), spec(described).get("bedVolume"));
        assertEquals(List.of("prusa-shelf"), spec(described).get("tags"));
    }

    /**
     * There is no request that reaches the profile columns, which keeps "discovered distinguishable
     * from typed" true at the edge, not only in the schema.
     */
    @Test
    void anEditCannotTouchWhatTheMachineReported() {
        var printer = machine(MockPrinter.marlin());
        var id = register("renamed", printer);
        var events = connectAndLearn(id);
        awaitRoster(id, events, () -> firmware(describe(id)).containsKey("FIRMWARE_NAME"),
                "the roster to learn the firmware");
        var reported = firmware(describe(id));

        update(id, "a different name", printer.port(), DECLARED);

        var described = describe(id);
        assertEquals("a different name", described.get("name"));
        assertEquals(reported, firmware(described), "the firmware half must be untouched");
    }

    /**
     * A dual-extruder machine flashed with single-extruder firmware reports one tool regardless, so
     * without the override every command for tool 1 is refused with no way to say otherwise.
     *
     * <p>Also covers the half easiest to leave out: the edit must reach the <em>running</em>
     * aggregate, not just the database - without {@code PrinterReactorManager.redeclare} this would
     * pass every roster check and still drop the command until a restart.
     */
    @Test
    void aDeclaredExtruderCountLetsACommandForTheSecondToolThrough() {
        var printer = machine(MockPrinter.marlin());
        var id = register("under-reports", printer);
        // connectAndSettle, because what is under test is the aggregate's own extruder count - the
        // roster's copy is not consulted by the refusal below.
        connectAndSettle(id, printer);

        // Before: the firmware says one tool, so a target for the second goes nowhere.
        command(id, "/set-temp", "{\"index\":1,\"temp\":210}").expectStatus().isOk();
        quiet();
        assertFalse(printer.sent("M104"), "the firmware reports one extruder, so this is refused");

        update(id, "under-reports", printer.port(), "{\"extruderCount\":2}");

        // After: the same request, to the same running machine, reaches the wire.
        command(id, "/set-temp", "{\"index\":1,\"temp\":210}").expectStatus().isOk();
        await("M104 for the second tool", () -> printer.sent("M104"));
        assertTrue(printer.firstSent("M104").contains("T1"));
    }

    /** Clearing the declaration leaves the firmware answering again - also on the wire. */
    @Test
    void clearingTheDeclarationFallsBackToTheFirmware() {
        var printer = machine(MockPrinter.marlin());
        var id = register("cleared", printer);
        connectAndSettle(id, printer);
        update(id, "cleared", printer.port(), "{\"extruderCount\":2}");
        command(id, "/set-temp", "{\"index\":1,\"temp\":210}").expectStatus().isOk();
        await("the override to take effect", () -> printer.sent("M104"));
        printer.forget();

        update(id, "cleared", printer.port(), null);

        assertNull(spec(describe(id)).get("extruderCount"), "nobody is declaring anything now");
        command(id, "/set-temp", "{\"index\":1,\"temp\":210}").expectStatus().isOk();
        quiet();
        assertFalse(printer.sent("M104"), "back to what the machine says about itself");
    }

    /**
     * A reactor is a resource - a socket, a send window, a command queue and a replay sink keeping
     * every SSE subscriber referenced. The map this lives in used to only ever grow, so a deleted
     * printer left all of that behind, still connected; the socket closing here is the fix,
     * asserted from the only side that can see it.
     */
    @Test
    void deletingAMachineClosesItsConnection() {
        var printer = machine(MockPrinter.marlin());
        var id = register("deleted", printer);
        connectAndWait(id);
        assertTrue(printer.sessionOpen());

        farm.delete().uri("/api-manager/{id}", id).exchange().expectStatus().isOk();

        await("the socket to close at the machine", () -> !printer.sessionOpen());
        var roster = farm.get().uri("/api-manager").exchange()
                .expectBody(String.class).returnResult().getResponseBody();
        assertFalse(roster.contains(id.toString()), "and the farm should have forgotten it");
    }

    /**
     * #65: this was a <strong>500</strong>, from the signature rather than the handler -
     * {@code MachineManagementService.fetch} answered a {@code Descriptor} via {@code orElseThrow},
     * so "no such machine" had no value to arrive as. The three other GET-by-id endpoints already
     * answered 404; this was the outlier, known debt that bit four times before being fixed.
     *
     * <p>The empty body is part of the claim: a query's absence is said entirely by the status, and
     * the browser's {@code getJson} calls {@code response.json()} on whatever arrives, so 200 with
     * nothing in it is a parse error rather than something a caller can branch on.
     */
    @Test
    void askingForAMachineTheFarmDoesNotHaveIsNotFound() {
        farm.get().uri("/api-manager/{id}", UUID.randomUUID()).exchange()
                .expectStatus().isNotFound()
                .expectBody().isEmpty();
    }

    /**
     * The realistic version of the test above: nobody types a random UUID, but a page left open on
     * a removed printer asks for exactly this. Pins the before-and-after in one test, so a
     * regression that made every id 404 would fail on the first line.
     */
    @Test
    void aMachineThatHasBeenDeletedIsNotFoundAfterwards() {
        var printer = machine(MockPrinter.marlin());
        var id = register("here and then gone", printer);
        assertEquals("here and then gone", describe(id).get("name"));

        deregister(id);

        farm.get().uri("/api-manager/{id}", id).exchange().expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Only ever called after {@link #connectAndLearn}, so the report has demonstrably reached
     * {@code core} - a timeout here names the one hop left, {@code PrinterReactor.report}
     * publishing {@code MFirmwareReportedEvent} or {@code MachineManagementImpl} storing it.
     *
     * <p>That hop is <strong>at-most-once and unretried</strong>: {@code PrinterState.on(FirmwareInfoRs)}
     * sets its own {@code profile} before emitting and guards on
     * {@code updated.firmware().equals(profile.firmware())}, so a second identical {@code M115}
     * (from a redial) is suppressed and the roster never gets another chance - a loss here is
     * permanent, not late.
     */
    private void awaitRoster(java.util.UUID id, Events events,
                             java.util.function.BooleanSupplier condition, String what) {
        await(() -> what + ". The aggregate emitted FirmwareInfoRs (the stream carried "
                        + events.types() + ") and the machine received " + received(id)
                        + ", so the report reached core: the loss is the core -> management hand-off",
                condition);
    }

    /** The edit endpoint. {@code PUT} and not {@code PATCH}: the body is the whole declaration. */
    private void update(java.util.UUID id, String name, int port, String specJson) {
        var body = "{\"name\":\"" + name + "\",\"host\":\"localhost\",\"port\":" + port
                + (specJson == null ? "" : ",\"spec\":" + specJson) + "}";
        farm.put().uri("/api-manager/{id}", id)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(Map<String, Object> described) {
        return (Map<String, Object>) described.get("spec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firmware(Map<String, Object> described) {
        var profile = (Map<String, Object>) described.get("profile");
        return (Map<String, Object>) profile.get("firmware");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capabilities(Map<String, Object> described) {
        var profile = (Map<String, Object>) described.get("profile");
        return (Map<String, Object>) profile.get("capabilities");
    }
}
