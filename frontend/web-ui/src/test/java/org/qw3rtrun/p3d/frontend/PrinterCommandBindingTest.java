package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.PrinterCommand;
import org.qw3rtrun.p3d.api.msg.RequestTemperatureReport;
import org.qw3rtrun.p3d.api.msg.SetBedTarget;
import org.qw3rtrun.p3d.api.msg.SetHotendTarget;
import org.qw3rtrun.p3d.api.msg.SetTemperatureAutoReport;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.reactivestreams.Publisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JSON the browser posts, and the command it becomes.
 *
 * <p>A contract with code the build does not compile: {@code static/js/lib/client.js} sends
 * {@code {index, temp}}, {@code {temp}}, {@code {period}} and {@code {connect}}, and nothing else
 * in this repository would notice if a field stopped binding. Something already had:
 * {@code /auto-report-temp} bound `:gcode`'s generated class, whose property is {@code seconds},
 * so the {@code period} the user chose matched no field and every request silently asked for the
 * firmware's default instead.
 *
 * <p>That is the argument for the application owning its own command records rather than exposing
 * Marlin's. These assertions are what keeps the two sides agreeing from here.
 */
class PrinterCommandBindingTest {

    private final UUID id = UUID.randomUUID();
    private final AtomicReference<PrinterCommand> received = new AtomicReference<>();
    private final AtomicReference<ConnectCmd> connected = new AtomicReference<>();

    private WebTestClient client() {
        PrinterControl control = new PrinterControl() {
            @Override
            public Publisher<TemperatureReport> state(UUID id) {
                return Mono.empty();
            }

            @Override
            public Publisher<EventMessage> updates(UUID id) {
                return Mono.empty();
            }

            @Override
            public Publisher<Void> send(UUID id, PrinterCommand command) {
                received.set(command);
                return Mono.empty();
            }

            @Override
            public Publisher<Void> connect(UUID id, ConnectCmd command) {
                connected.set(command);
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

    /** {@code client.setHeatingTemp(index, temp)}. */
    @Test
    void theHotendPayloadBindsBothFields() {
        post("/set-temp", "{\"index\":1,\"temp\":210.5}");

        assertEquals(new SetHotendTarget(1, new BigDecimal("210.5")), received.get());
    }

    /** {@code client.stopHeating(index)} - the same shape, asking for zero. */
    @Test
    void stoppingAHotendIsATargetOfZero() {
        post("/set-temp", "{\"index\":0,\"temp\":0}");

        assertEquals(new SetHotendTarget(0, new BigDecimal("0")), received.get());
    }

    /** {@code client.setBedTemp(temp)}. */
    @Test
    void theBedPayloadBinds() {
        post("/set-bed-temp", "{\"temp\":60}");

        assertEquals(new SetBedTarget(new BigDecimal("60")), received.get());
    }

    /**
     * {@code client.autoReportTemp(period)} - the one that was silently dropping its argument.
     */
    @Test
    void theAutoReportPeriodArrives() {
        post("/auto-report-temp", "{\"period\":2}");

        assertEquals(new SetTemperatureAutoReport(2), received.get());
    }

    /** {@code client.api(uuid).connect()} and {@code disconnect()}. */
    @Test
    void theConnectPayloadBinds() {
        post("/connect", "{\"connect\":true}");
        assertEquals(new ConnectCmd(true), connected.get());

        post("/connect", "{\"connect\":false}");
        assertEquals(new ConnectCmd(false), connected.get());
    }

    /** {@code POST /report-temp} carries no body at all. */
    @Test
    void anOnDemandReportNeedsNoBody() {
        client().post().uri("/api-printer/" + id + "/report-temp").exchange().expectStatus().isOk();

        assertEquals(new RequestTemperatureReport(null), received.get());
    }
}
