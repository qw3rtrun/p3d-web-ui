package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.BusyReport;
import org.qw3rtrun.p3d.api.msg.CapabilityReport;
import org.qw3rtrun.p3d.api.msg.FirmwareReport;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.qw3rtrun.p3d.api.msg.TemperatureReported;
import org.qw3rtrun.p3d.api.msg.UnknownReply;
import org.qw3rtrun.p3d.terminal.TerminalManager;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reflective dispatch in {@link PrinterReactor#invoke}, over the events the transport publishes.
 *
 * <p>Worth its own test because the failure mode is silence. {@code invoke} matches a message to a
 * {@link PrinterState} handler by walking its supertypes, and nothing about that is checked by the
 * compiler: a renamed handler method or a record that stops implementing its event interface
 * unregisters itself, and the only sign is a {@code No invoker for} at WARN. Nothing throws; the
 * temperature just stops moving. Plan item A5 replaces the whole mechanism with a sealed switch,
 * and until then this is what holds it.
 *
 * <p><strong>The messages are {@code api} events now, not {@code :gcode} replies.</strong> This
 * test used to feed {@code MarlinRsDecoder.decode(…)} straight in, because {@code GRs} extended
 * {@code GEvent} and {@code OkTemperatureRs} reached {@code TemperatureReportedEvent} two
 * interfaces out - which was the precise thing it was written to pin. That chain is gone: the two
 * models are separate and {@code :backend:terminal} converts between them, so what arrives here is
 * what the bridge produces. The supertype walk is one level shallower as a result, and the test is
 * about the dispatch rather than about the protocol's class hierarchy.
 *
 * <p>No connection is opened: {@link TerminalManager}'s constructor only builds a {@code TcpClient}.
 */
class PrinterReactorDispatchTest {

    private PrinterState printer;
    private PrinterReactor reactor;

    private void newReactor() {
        printer = new PrinterState(UUID.randomUUID());
        reactor = new PrinterReactor(new TerminalManager(new ConnectionDetails("localhost", 1)), printer);
    }

    @Test
    void aTemperatureReachesThePrinterThroughItsEventInterface() {
        newReactor();

        reactor.invoke(new TemperatureReported(
                new TemperatureReport(210.0, 205.0, 127),
                new TemperatureReport(60.0, 55.0, 80)));

        assertEquals(210.0, printer.getTemperature().current());
        assertEquals(205.0, printer.getTemperature().target());
        assertEquals(127, printer.getTemperature().power());
    }

    @Test
    void aReportWithNoBedReachesItToo() {
        newReactor();

        reactor.invoke(new TemperatureReported(new TemperatureReport(24.31, 0.0, 0), null));

        assertEquals(24.31, printer.getTemperature().current());
    }

    @Test
    void firmwareAndCapabilitiesStillDispatch() {
        newReactor();

        reactor.invoke(new FirmwareReport(Map.of("FIRMWARE_NAME", "Marlin 2.1.2", "EXTRUDER_COUNT", "1")));
        reactor.invoke(new CapabilityReport("Cap:EEPROM:1", "EEPROM", true));
    }

    @Test
    void anEventWithNoHandlerIsIgnoredRatherThanThrown() {
        newReactor();
        // Neither has a PrinterState handler. An unhandled event is a gap in coverage, not a fault,
        // and the event stream has to survive one - an UnknownReply is the normal case, since the
        // protocol decodes a good deal more than the host acts on.
        reactor.invoke(new BusyReport("processing"));
        reactor.invoke(new UnknownReply("X:0.00 Y:0.00 Z:5.00"));
    }
}
