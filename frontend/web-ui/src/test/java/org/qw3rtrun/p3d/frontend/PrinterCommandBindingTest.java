package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON the browser posts, and the G-code it becomes.
 *
 * <p>A contract the Java build cannot see: {@code client.ts} sends {@code {index, temp}},
 * {@code {temp}}, {@code {period}}, {@code {connect}}, and {@code vue-tsc} only checks that file
 * against itself, not against these records. Not hypothetical: {@code /auto-report-temp} once bound
 * `:gcode`'s generated class, whose property is {@code seconds}, so the {@code period} chosen
 * matched no field and every request silently asked for the firmware's default instead - the
 * argument for request bodies being this module's own records rather than Marlin's.
 *
 * <p>Asserted as the command that comes out, not the record that bound: the controller translates
 * before calling {@link PrinterControl}, so a field that binds but is dropped on the way to Marlin
 * reads here as a missing {@code S2} - the stronger assertion.
 */
class PrinterCommandBindingTest {

    private final UUID id = UUID.randomUUID();
    private final AtomicReference<List<String>> received = new AtomicReference<>(List.of());
    private final AtomicReference<ConnectCmd> connected = new AtomicReference<>();

    /** What the upload endpoint asked for, as {@code fileId -> name on the card}. */
    private final AtomicReference<String> uploaded = new AtomicReference<>();

    private final AtomicBoolean cancelled = new AtomicBoolean();

    // What the stubbed runtime answers an upload with (accepted unless a test says otherwise) - a
    // field rather than a second stub class, since the refusal tests want the real controller and
    // Jackson encoding, asserting only the status and body bytes a browser can see.
    private final AtomicReference<ControlOutcome> uploadAnswers = new AtomicReference<>();

    private WebTestClient client() {
        PrinterControl control = new PrinterControl() {
            @Override
            public Publisher<HeaterReading> state(UUID id) {
                return Mono.empty();
            }

            @Override
            public Publisher<EventMessage> updates(UUID id) {
                return Mono.empty();
            }

            @Override
            public Publisher<Void> send(UUID id, List<GRq<?>> commands) {
                received.set(commands.stream()
                        .map(c -> GEncoder.INSTANCE.encode(c.encode()))
                        .toList());
                return Mono.empty();
            }

            @Override
            public Publisher<Void> connect(UUID id, ConnectCmd command) {
                connected.set(command);
                return Mono.empty();
            }

            @Override
            public Publisher<ConnectionStatus> connection(UUID id) {
                return Mono.empty();
            }

            @Override
            public Publisher<ControlOutcome> uploadToCard(UUID id, UUID fileId, String cardName) {
                uploaded.set(fileId + " -> " + cardName);
                var answer = uploadAnswers.get();
                return Mono.just(answer == null ? ControlOutcome.accepted(id) : answer);
            }

            @Override
            public Publisher<ControlOutcome> cancelCardUpload(UUID id) {
                cancelled.set(true);
                return Mono.just(ControlOutcome.accepted(id));
            }
        };
        return WebTestClient.bindToController(new PrinterController(control)).build();
    }

    private void post(String path, String json) {
        client().post().uri("/api-printer/" + id + path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isOk();
    }

    /** The one command the request became. */
    private String sent() {
        var commands = received.get();
        assertEquals(1, commands.size(), () -> "expected one command, got " + commands);
        return commands.getFirst();
    }

    /** {@code client.setHotendTarget(index, temp)}. */
    @Test
    void theHotendPayloadBindsBothFields() {
        post("/set-temp", "{\"index\":1,\"temp\":210.5}");

        assertEquals("M104 S210.5 T1", sent());
    }

    /** {@code client.stopHeating(index)} - the same shape, asking for zero. */
    @Test
    void stoppingAHotendIsATargetOfZero() {
        post("/set-temp", "{\"index\":0,\"temp\":0}");

        assertEquals("M104 S0 T0", sent());
    }

    /** {@code client.setBedTarget(temp)}. */
    @Test
    void theBedPayloadBinds() {
        post("/set-bed-temp", "{\"temp\":60}");

        assertEquals("M140 S60", sent());
    }

    // client.autoReportTemperature(period) - the one that was silently dropping its argument; S2
    // is the whole point of this test.
    @Test
    void theAutoReportPeriodArrives() {
        post("/auto-report-temp", "{\"period\":2}");

        assertEquals("M155 S2", sent());
    }

    /** {@code client.api(uuid).connect()} and {@code disconnect()}. */
    @Test
    void theConnectPayloadBinds() {
        post("/connect", "{\"connect\":true}");
        assertEquals(new ConnectCmd(true), connected.get());

        post("/connect", "{\"connect\":false}");
        assertEquals(new ConnectCmd(false), connected.get());
    }

    // ------------------------------------------------- the Marlin-screen controls

    /** {@code POST /home} with no axis named means all of them. */
    @Test
    void homingBindsItsAxesAndDefaultsToAll() {
        post("/home", "{}");
        assertEquals("G28", sent());

        post("/home", "{\"x\":true,\"z\":true}");
        assertEquals("G28 X Z", sent());
    }

    // Null and zero differ on the wire: null is an axis the move does not mention, zero in
    // relative mode means "do not move this axis" - a binding that defaulted null to zero would
    // turn a single-axis jog into a move of everything.
    @Test
    void aJogBindsItsAxesAndItsMode() {
        post("/move", "{\"x\":10,\"feedRate\":3000,\"relative\":true}");

        assertEquals(List.of("G91", "G1 X10 F3000", "G90"), received.get());
    }

    /** An absolute move is the same payload without the flag. */
    @Test
    void anAbsoluteMoveBinds() {
        post("/move", "{\"x\":0,\"y\":0,\"feedRate\":6000}");

        assertEquals("G1 X0 Y0 F6000", sent());
    }

    /** {@code POST /fan} on Marlin's 0..255 scale, not a percentage. */
    @Test
    void theFanPayloadBinds() {
        post("/fan", "{\"index\":0,\"speed\":128}");
        assertEquals("M106 S128 P0", sent());

        post("/fan", "{\"speed\":0}");
        assertEquals("M107", sent());
    }

    /** {@code POST /store-settings} takes an empty object - a deliberate write, not a link. */
    @Test
    void storingSettingsBinds() {
        post("/store-settings", "{}");
        assertEquals("M500", sent());
    }

    /** {@code POST /position} carries no body; the answer arrives on the stream. */
    @Test
    void askingForThePositionNeedsNoBody() {
        client().post().uri("/api-printer/" + id + "/position").exchange().expectStatus().isOk();

        assertEquals("M114", sent());
    }

    /** {@code POST /report-temp} carries no body at all. */
    @Test
    void anOnDemandReportNeedsNoBody() {
        client().post().uri("/api-printer/" + id + "/report-temp").exchange().expectStatus().isOk();

        assertEquals("M105", sent());
    }

    // ------------------------------------------------- the Settings tab

    // No body, not {}: a @RequestBody on any of these would answer 400 to a request the browser is
    // entitled to make, and client.ts happens to send {} so the mistake would otherwise hide.
    @Test
    void theFourSettingsOperationsNeedNoBody() {
        client().post().uri("/api-printer/" + id + "/settings/read").exchange().expectStatus().isOk();
        assertEquals("M503", sent());

        client().post().uri("/api-printer/" + id + "/settings/revert").exchange().expectStatus().isOk();
        assertEquals("M501", sent());

        client().post().uri("/api-printer/" + id + "/settings/defaults").exchange().expectStatus().isOk();
        assertEquals("M502", sent());

        client().post().uri("/api-printer/" + id + "/settings/validate").exchange().expectStatus().isOk();
        assertEquals("M504", sent());
    }

    // Asserted as the wire text, not the record, so a field written under the wrong letter shows
    // up here - home offset is the one that would catch it, since its generated class's first
    // parameter writes P, the tool index.
    @Test
    void everySettingPayloadBindsAndKeepsItsLetter() {
        post("/settings/steps-per-unit", "{\"x\":81}");
        assertEquals("M92 X81", sent());

        post("/settings/max-feedrate", "{\"x\":250,\"z\":5}");
        assertEquals("M203 X250 Z5", sent());

        post("/settings/max-acceleration", "{\"x\":2500}");
        assertEquals("M201 X2500", sent());

        post("/settings/acceleration", "{\"print\":2000,\"retract\":3000,\"travel\":4000}");
        assertEquals("M204 P2000 R3000 T4000", sent());

        post("/settings/advanced", "{\"minSegmentTimeUs\":20000,\"junctionDeviation\":0.02}");
        assertEquals("M205 B20000 J0.02", sent());

        post("/settings/home-offset", "{\"z\":-0.1}");
        assertEquals("M206 Z-0.1", sent());
    }

    // Not as a zero: client.ts sends only the fields the operator typed, and an absent field
    // binding to something would mean M92 X81 Y0 Z0 E0 stopping three axes, or M206 X0 Y0 Z-0.1
    // silently re-homing two nobody mentioned.
    @Test
    void anAbsentFieldSendsNoWord() {
        post("/settings/steps-per-unit", "{\"e\":500}");
        assertEquals("M92 E500", sent());

        post("/settings/home-offset", "{\"z\":-0.1}");
        assertTrue(!sent().contains("X"), "an unmentioned axis must not be zeroed");
    }

    // BigDecimal, so 0.013 is not rounded and 80.00 is not shortened to 80 - a real machine's
    // junction deviation is three decimal places, and normalising it would be making up digits.
    @Test
    void aSettingKeepsThePrecisionItWasSentWith() {
        post("/settings/advanced", "{\"junctionDeviation\":0.013}");
        assertEquals("M205 J0.013", sent());

        post("/settings/steps-per-unit", "{\"z\":400.00}");
        assertEquals("M92 Z400.00", sent());
    }

    // ------------------------------------------------- the Storage tab

    /** The three that ask for nothing and so carry no body. */
    @Test
    void mountReleaseAndListNeedNoBody() {
        client().post().uri("/api-printer/" + id + "/sd/mount").exchange().expectStatus().isOk();
        assertEquals("M21", sent());

        client().post().uri("/api-printer/" + id + "/sd/release").exchange().expectStatus().isOk();
        assertEquals("M22", sent());

        client().post().uri("/api-printer/" + id + "/sd/list").exchange().expectStatus().isOk();
        assertEquals("M20", sent());
    }

    @Test
    void theDeletePayloadBindsItsName() {
        post("/sd/delete", "{\"name\":\"BENCHY.GCO\"}");

        assertEquals("M30 BENCHY.GCO", sent());
    }

    /**
     * Printing is two commands, so this is the one SD request that is not a single line - and two
     * by choice rather than by accident: #109 and {@code PrintFile} say why one {@code M32} is not
     * it.
     */
    @Test
    void thePrintPayloadBecomesSelectThenStart() {
        post("/sd/print", "{\"name\":\"BENCHY.GCO\"}");

        assertEquals(List.of("M23 BENCHY.GCO", "M24"), received.get());
    }

    // The status is the point: the check lives in a record's compact constructor and throws
    // IllegalArgumentException, so a body that fails to construct is a 400 decoding failure, where
    // the same exception thrown later in a handler would be a 500. The payload is the injection
    // itself: a filename is a bare rest-of-line string, so "a.gco\nM112" is a delete and an
    // emergency stop.
    @Test
    void aFilenameCarryingANewlineIsRejectedAsABadRequest() {
        client().post().uri("/api-printer/" + id + "/sd/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"a.gco\\nM112\"}")
                .exchange()
                .expectStatus().isBadRequest();

        assertEquals(List.of(), received.get(), "nothing may reach the printer");
    }

    // Names a stored file; carries no bytes - those go to /api-files first, two requests rather
    // than one, so the same file can go to a second machine without being sent again.
    @Test
    void theUploadPayloadNamesAStoredFileAndItsNameOnTheCard() {
        var fileId = UUID.randomUUID();

        post("/sd/upload", "{\"fileId\":\"" + fileId + "\",\"name\":\"BENCHY.GCO\"}");

        assertEquals(fileId + " -> BENCHY.GCO", uploaded.get());
        assertEquals(List.of(), received.get(), "an upload is not a command on the send path");
    }

    // Not required of the browser - the server adds one, since M20 lists only printable
    // extensions and a name without one would be on the card and invisible.
    @Test
    void anUploadNameNeedsNoExtensionHere() {
        var fileId = UUID.randomUUID();

        post("/sd/upload", "{\"fileId\":\"" + fileId + "\",\"name\":\"benchy\"}");

        assertEquals(fileId + " -> benchy", uploaded.get());
    }

    /** Cancelling needs no body, and is fine when nothing is running. */
    @Test
    void cancellingAnUploadNeedsNoBody() {
        client().post().uri("/api-printer/" + id + "/sd/upload/cancel").exchange().expectStatus().isOk();

        assertTrue(cancelled.get());
    }

    /** The same filename check as delete and print, on the request that actually writes the file. */
    @Test
    void anUploadNameCarryingANewlineIsRejectedAsABadRequest() {
        client().post().uri("/api-printer/" + id + "/sd/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"fileId\":\"" + UUID.randomUUID() + "\",\"name\":\"a.gco\\nM112\"}")
                .exchange()
                .expectStatus().isBadRequest();

        assertNull(uploaded.get(), "nothing may reach the printer");
    }

    /** A request naming no file is a bad request, not a transfer of nothing. */
    @Test
    void anUploadWithNoFileIsRejectedAsABadRequest() {
        client().post().uri("/api-printer/" + id + "/sd/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"benchy.gco\"}")
                .exchange()
                .expectStatus().isBadRequest();

        assertNull(uploaded.get());
    }

    @Test
    void anEmptyFilenameIsRejectedAsABadRequest() {
        client().post().uri("/api-printer/" + id + "/sd/print")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest();

        assertEquals(List.of(), received.get(), "nothing may reach the printer");
    }

    // ------------------------------------------------- the refusals (issue #65)

    /** Posts an upload the runtime will answer with {@code outcome}, and hands back the response. */
    private WebTestClient.ResponseSpec uploadAnswering(ControlOutcome outcome) {
        uploadAnswers.set(outcome);
        return client().post().uri("/api-printer/" + id + "/sd/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"fileId\":\"" + UUID.randomUUID() + "\",\"name\":\"BENCHY.GCO\"}")
                .exchange();
    }

    // Used to be a 500: the uploader threw IllegalArgumentException for a file the store does not
    // hold, nothing classified it. refusal and reason are what client.ts reads, uncompiled against
    // this build, so the field names are what can rot silently.
    @Test
    void anAbsentUploadTargetIsNotFoundWithItsReasonInTheBody() {
        uploadAnswering(ControlOutcome.absent(id, "there is no file to upload"))
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.machineId").isEqualTo(id.toString())
                .jsonPath("$.refusal").isEqualTo("ABSENT")
                .jsonPath("$.reason").isEqualTo("there is no file to upload");
    }

    // 409, not 404: the printer is there, and connecting it is all the operator has to do - a 404
    // here would tell somebody their machine does not exist because it happened to be unplugged.
    @Test
    void anUploadToAMachineInTheWrongStateIsAConflict() {
        uploadAnswering(ControlOutcome.wrongState(id, "printer " + id + " is not connected"))
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.machineId").isEqualTo(id.toString())
                .jsonPath("$.refusal").isEqualTo("WRONG_STATE")
                .jsonPath("$.reason").isEqualTo("printer " + id + " is not connected");
    }
}
