package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.terminal.GSender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bytes {@link PrinterState} puts on the wire, pinned across the move from the old {@code G}
 * facade to {@link GSender}. Every expectation was captured from the old facade before deletion, so
 * this is a characterisation test: it passes against both implementations, except the one deliberate
 * exception, {@link #aTemperatureIsNotFormattedThroughTheDefaultLocale()}, which the old code failed.
 */
class PrinterStateGcodeTest {

    private final List<String> sent = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();

    // an exact void method reference, not sent::add: List.add is overloaded, making the reference
    // ambiguous across GSender's two constructors - same reason PrinterReactor uses one.
    private void capture(String gcode) {
        sent.add(gcode);
    }

    private PrinterState online() {
        PrinterState printer = new PrinterState(UUID.randomUUID());
        printer.setEmitter(events::add);
        printer.onOnline(new GSender(this::capture));
        return printer;
    }

    private PrinterState onlineWithExtruders(int count) {
        PrinterState printer = online();
        printer.on(firmwareReporting(count));
        sent.clear();
        return printer;
    }

    @Test
    void theOnlineHandshakeAsksForFirmwareInfoThenAutoReporting() {
        online();

        assertEquals(List.of("M115", "M155 S1"), sent);
    }

    @Test
    void aBedTemperatureIsSentAsM140WithTwoDecimals() {
        PrinterState printer = online();
        sent.clear();

        printer.handle(MarlinG.INSTANCE.m140(new BigDecimal("60.0")));
        printer.handle(MarlinG.INSTANCE.m140(new BigDecimal("60.456")));

        assertEquals(List.of("M140 S60.00", "M140 S60.46"), sent);
    }

    @Test
    void aHotendTemperatureIsSentAsM104WithTheToolIndex() {
        PrinterState printer = onlineWithExtruders(2);

        printer.handle(MarlinG.INSTANCE.m104(new BigDecimal("60.0"), 0));
        printer.handle(MarlinG.INSTANCE.m104(new BigDecimal("210.5"), 1));

        assertEquals(List.of("M104 S60.00 T0", "M104 S210.50 T1"), sent);
    }

    @Test
    void aHotendIndexTheFirmwareDoesNotHaveSendsNothing() {
        PrinterState printer = onlineWithExtruders(2);

        printer.handle(MarlinG.INSTANCE.m104(new BigDecimal("60.0"), 5));

        assertEquals(List.of(), sent);
    }

    // changed with the move off the old records: a bare request used to encode as M105 T0 (the
    // record defaulted its index to 0, with no "absent"); M105 and M105 T0 are different commands
    // (active tool vs tool 0), and a bare request now says so.
    @Test
    void aTemperatureReportIsSentAsM105WithTheToolIndex() {
        PrinterState printer = online();
        sent.clear();

        printer.handle(MarlinG.INSTANCE.m105(null));
        printer.handle(MarlinG.INSTANCE.m105(2));

        assertEquals(List.of("M105", "M105 T2"), sent);
    }

    // changed with the move off the old records: an absent period used to encode as M155 S0 (turns
    // auto-reporting off), an accident of the record having no absent state; it now encodes as a
    // bare M155 ("change nothing").
    @Test
    void autoReportingIsSentAsM155WithThePeriod() {
        PrinterState printer = online();
        sent.clear();

        printer.handle(MarlinG.INSTANCE.m155(1));
        printer.handle(MarlinG.INSTANCE.m155(0));
        printer.handle(MarlinG.INSTANCE.m155(null));

        assertEquals(List.of("M155 S1", "M155 S0", "M155"), sent);
    }

    @Test
    void disconnectingTurnsAutoReportingOff() {
        PrinterState printer = online();
        printer.handle(new ConnectCmd(true));
        sent.clear();

        printer.handle(new ConnectCmd(false));

        assertEquals(List.of("M155 S0"), sent);
    }

    // String.format("%.2f", ...) used the default locale, so this emitted M140 S60,00 under a
    // comma-decimal locale - not a number by spec sect. 3.1, and a command the firmware rejects.
    @Test
    void aTemperatureIsNotFormattedThroughTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            PrinterState printer = online();
            sent.clear();
            printer.handle(MarlinG.INSTANCE.m140(new BigDecimal("60.0")));

            assertEquals(List.of("M140 S60.00"), sent);
        } finally {
            Locale.setDefault(original);
        }
    }

    // built by hand the same shape :gcode decodes, so the aggregate reads the count the way it does
    // in production - out of the map, not a named accessor only a test would implement.
    private static FirmwareInfoRs firmwareReporting(int extruderCount) {
        return new FirmwareInfoRs(
                Map.of("FIRMWARE_NAME", "Test", "EXTRUDER_COUNT", String.valueOf(extruderCount)));
    }

}
