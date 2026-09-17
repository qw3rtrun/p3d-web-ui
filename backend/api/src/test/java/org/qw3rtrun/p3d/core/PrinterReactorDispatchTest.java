package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.core.model.ConnectionDetails;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.marlin.event.OkTemperatureRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
import org.qw3rtrun.p3d.terminal.TerminalManager;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The reflective dispatch in {@link PrinterReactor#invoke}, against the reply classes that replaced
 * the {@code marlin/decoder} package.
 *
 * <p>Worth its own test because the failure mode is silence. {@code invoke} matches a message to a
 * {@link PrinterState} handler by walking its supertypes, and the classes it used to be given each
 * declared their event interface directly - {@code OkTemperatureReported implements
 * TemperatureReportedEvent}. The classes that replaced them reach it one level further out
 * ({@code OkTemperatureRs -> TemperatureRs -> TemperatureReportedEvent}), so the old one-level
 * search over {@code getClass().getInterfaces()} would find only {@code TemperatureRs} and
 * {@code OkRs}, match no handler, and log "No invoker for" instead of updating the printer. Nothing
 * would throw; the temperature would just stop moving.
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
    void temperatureReachesThePrinterThroughItsFamilyInterface() {
        newReactor();
        GRs<?> reply = MarlinRsDecoder.INSTANCE.decode("ok T:210.00 /205.00 B:60.00 /55.00 @:127 B@:80");
        assertNotNull(reply);
        assertEquals(OkTemperatureRs.class, reply.getClass());

        reactor.invoke(reply);

        assertEquals(210.0, printer.getTemperature().current());
        assertEquals(205.0, printer.getTemperature().target());
        assertEquals(127, printer.getTemperature().power());
    }

    @Test
    void aBareReportReachesItToo() {
        newReactor();
        reactor.invoke(MarlinRsDecoder.INSTANCE.decode("T:24.31 /0.00 @:0"));

        assertEquals(24.31, printer.getTemperature().current());
    }

    @Test
    void firmwareAndCapabilitiesStillDispatch() {
        newReactor();
        // These two declare their event interface directly, so they worked before and must keep
        // working: the supertype walk has to find the nearest match, not merely some match.
        GRs<?> firmware = MarlinRsDecoder.INSTANCE.decode("FIRMWARE_NAME:Marlin 2.1.2 EXTRUDER_COUNT:1");
        assertEquals(FirmwareInfoRs.class, firmware.getClass());
        reactor.invoke(firmware);

        GRs<?> capability = MarlinRsDecoder.INSTANCE.decode("Cap:EEPROM:1");
        assertEquals(CapabilityRs.class, capability.getClass());
        reactor.invoke(capability);
    }

    @Test
    void aReplyWithNoHandlerIsIgnoredRatherThanThrown() {
        newReactor();
        // `wait` has no PrinterState handler. It must log and carry on - an unhandled reply is a
        // gap in coverage, not a fault, and the event stream has to survive one.
        reactor.invoke(MarlinRsDecoder.INSTANCE.decode("wait"));
        reactor.invoke(MarlinRsDecoder.INSTANCE.decode("busy: processing"));
    }
}
