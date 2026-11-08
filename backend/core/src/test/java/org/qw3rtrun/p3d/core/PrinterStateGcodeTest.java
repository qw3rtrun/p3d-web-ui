package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.terminal.GSender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bytes {@link PrinterState} puts on the wire, pinned across the move from the old {@code G}
 * facade to {@link GSender}. Every expectation was captured from the old facade before deletion, so
 * this is a characterisation test: it passes against both implementations, except two -
 * {@link #aTemperatureIsNotFormattedThroughTheDefaultLocale()}, which the old code failed, and
 * {@link #aDeclarationIsHonouredByTheVeryNextCommand()}, which pins a threading
 * contract rather than characterising anything.
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

    /**
     * A declaration decides the very next command, and clearing it falls back to the firmware's
     * count. That is plan item B4's whole promise to an operator, and it is what this asserts.
     *
     * <p>The edit is still made from a thread of its own, <strong>joined</strong>, and that is now a
     * deliberately weaker premise than production's rather than a copy of it: since #80
     * {@code PrinterReactor.declared} queues the edit on this machine's own thread, so
     * {@code PrinterState.spec} is a plain field and the aggregate needs no edge of its own. Writing
     * it here from a joined thread says the aggregate is right under the weakest ordering anybody
     * could hand it, without pretending {@code declared()} may be called from anywhere - it may not.
     *
     * <p>{@code spec} was {@code volatile} for a day, which this test's name used to assert. The
     * missing edge was found while investigating issue #93 and was <strong>not</strong> its cause -
     * adding {@code volatile} moved that failure rate not at all, because the write was visible and
     * simply had the wrong value; see {@code MSpecDeclaredEvent}'s KDoc for what #93 actually was.
     */
    @Test
    void aDeclarationIsHonouredByTheVeryNextCommand() throws InterruptedException {
        PrinterState printer = onlineWithExtruders(1);

        declaredFromAnotherThread(printer, new MachineSpec(null, 2, List.of(), Set.of(), Set.of()));
        printer.handle(MarlinG.INSTANCE.m104(new BigDecimal("210.0"), 1));
        declaredFromAnotherThread(printer, MachineSpec.undeclared());
        printer.handle(MarlinG.INSTANCE.m104(new BigDecimal("215.0"), 1));

        assertEquals(List.of("M104 S210.00 T1"), sent, "the declaration, then the fall back to one extruder");
    }

    private static void declaredFromAnotherThread(PrinterState printer, MachineSpec spec)
            throws InterruptedException {
        Thread editor = new Thread(() -> printer.declared(spec), "an-edit");
        editor.start();
        editor.join();
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
