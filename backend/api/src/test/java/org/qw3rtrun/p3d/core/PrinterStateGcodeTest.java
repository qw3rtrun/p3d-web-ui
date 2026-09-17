package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.core.msg.ConnectCmd;
import org.qw3rtrun.p3d.core.msg.FirmwareInfoReportEvent;
import org.qw3rtrun.p3d.core.msg.GEvent;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.terminal.GSender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bytes {@link PrinterState} puts on the wire, pinned across the move from the old {@code G}
 * facade to {@link GSender}.
 *
 * <p>Every expectation here was captured from the old facade before it was deleted, so this is a
 * characterisation test in the strict sense: it passes against both implementations, which is what
 * makes it evidence that the swap changed nothing observable. The one deliberate exception is
 * {@link #aTemperatureIsNotFormattedThroughTheDefaultLocale()} — the old code failed it.
 */
class PrinterStateGcodeTest {

    private final List<String> sent = new ArrayList<>();
    private final List<GEvent> events = new ArrayList<>();

    /**
     * An exact {@code void} method reference, not {@code sent::add}: {@code List.add} is overloaded,
     * which makes the reference inexact and therefore ambiguous across {@code GSender}'s two
     * constructors. The same reason {@code PrinterReactor} uses one.
     */
    private void capture(String gcode) {
        sent.add(gcode);
    }

    /** A printer that has completed the online handshake, leaving it in {@link #sent}. */
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
     * <p><b>Changed with the move off the old records.</b> A bare request used to encode as
     * {@code M105 T0}, because the record defaulted its index to 0 and had no way to say "absent".
     * The generated command distinguishes the two, and {@code M105} and {@code M105 T0} are
     * different commands: the first asks about the active tool, the second about tool 0. A bare
     * request now says so.
     */
    @Test
    void aTemperatureReportIsSentAsM105WithTheToolIndex() {
        PrinterState printer = online();
        sent.clear();

        printer.handle(MarlinG.INSTANCE.m105(null));
        printer.handle(MarlinG.INSTANCE.m105(2));

        assertEquals(List.of("M105", "M105 T2"), sent);
    }

    /**
     * <p><b>Changed with the move off the old records.</b> An absent period used to encode as
     * {@code M155 S0}, which turns auto-reporting *off*; it now encodes as a bare {@code M155},
     * which Marlin reads as "change nothing". The old default was an accident of the record having
     * no absent state, and {@link #disconnectingTurnsAutoReportingOff()} covers the path that
     * actually means to turn it off - it asks for {@code S0} explicitly.
     */
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

    /**
     * The one behaviour that changed, and the reason it had to. {@code String.format("%.2f", …)}
     * used the default locale, so this emitted {@code M140 S60,00} under a comma-decimal locale —
     * not a number by spec §3.1, and a command the firmware rejects.
     */
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

    private static FirmwareInfoReportEvent firmwareReporting(int extruderCount) {
        return new FirmwareInfoReportEvent() {
            public String fullReportString() {
                return "FIRMWARE_NAME:Test EXTRUDER_COUNT:" + extruderCount;
            }

            public UUID uuid() {
                return UUID.randomUUID();
            }

            public String firmwareName() {
                return "Test";
            }

            public String srcCodeUrl() {
                return "";
            }

            public String protocolVersion() {
                return "";
            }

            public String machineType() {
                return "";
            }

            public int extruderCount() {
                return extruderCount;
            }
        };
    }
}
