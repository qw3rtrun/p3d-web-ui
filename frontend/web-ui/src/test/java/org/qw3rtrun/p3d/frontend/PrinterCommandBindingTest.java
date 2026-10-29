package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.reactivestreams.Publisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JSON the browser posts, and the G-code it becomes.
 *
 * <p>A contract with code the Java build does not compile: {@code src/frontend/src/api/client.ts}
 * sends {@code {index, temp}}, {@code {temp}}, {@code {period}} and {@code {connect}}, and nothing
 * else in this repository would notice if a field stopped binding - {@code vue-tsc} checks that
 * file against itself, not against these records. Something already had:
 * {@code /auto-report-temp} bound `:gcode`'s generated class, whose property is {@code seconds}, so
 * the {@code period} the user chose matched no field and every request silently asked for the
 * firmware's default instead.
 *
 * <p>That is the argument for the request bodies being this module's own records rather than
 * Marlin's generated classes, and it is the whole of what survives here now that the application
 * speaks {@code :gcode} everywhere else.
 *
 * <p><strong>Asserted as the command that comes out, not the record that bound.</strong> The
 * controller translates before it calls {@link PrinterControl}, so the G-code is what this test can
 * see - and it is the stronger assertion anyway: a field that binds into a record but is dropped on
 * the way to Marlin is the bug above, and it reads as a missing {@code S2} here.
 */
class PrinterCommandBindingTest {

    private final UUID id = UUID.randomUUID();
    private final AtomicReference<List<String>> received = new AtomicReference<>(List.of());
    private final AtomicReference<ConnectCmd> connected = new AtomicReference<>();

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

    /**
     * {@code client.autoReportTemperature(period)} - the one that was silently dropping its
     * argument. {@code S2} is the whole point of this test.
     */
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

    /**
     * A jog carries {@code relative}, and an omitted axis stays null.
     *
     * <p>Null and zero are different here and the difference reaches the wire: a null axis is one
     * the move does not mention, while zero in relative mode means "do not move this axis" and in
     * absolute mode means "go to the origin". A binding that defaulted null to zero would turn a
     * single-axis jog into a move of everything - which is why the expectation below names X and
     * nothing else.
     */
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
}
