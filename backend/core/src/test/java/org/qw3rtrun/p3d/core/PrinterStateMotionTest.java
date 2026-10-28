package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.HomeAxes;
import org.qw3rtrun.p3d.api.msg.MoveAxes;
import org.qw3rtrun.p3d.api.msg.PrinterCommand;
import org.qw3rtrun.p3d.api.msg.RequestPosition;
import org.qw3rtrun.p3d.api.msg.SetFanSpeed;
import org.qw3rtrun.p3d.api.msg.StoreSettings;
import org.qw3rtrun.p3d.terminal.GSender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commands a Marlin screen offers, as the bytes they put on the wire.
 *
 * <p>These assert the <strong>wire format</strong> and not the object graph, because the object
 * graph is not what a printer reads. The translation has two halves - an application command
 * becomes Marlin commands in {@link PrinterControlService#toMarlin}, and those become text in the
 * aggregate - and only the end of that chain is a fact about the machine.
 *
 * <p>Both halves are driven here deliberately: a test that called {@code toMarlin} and stopped
 * would pass while the aggregate silently dropped the result on its {@code default} arm, which is
 * exactly the state this suite found the code in.
 */
class PrinterStateMotionTest {

    private final List<String> sent = new ArrayList<>();

    /** A method reference, not a lambda - {@link GSender} has two single-argument constructors. */
    private void capture(String gcode) {
        sent.add(gcode);
    }

    private PrinterState online() {
        PrinterState printer = new PrinterState(UUID.randomUUID());
        printer.setEmitter(event -> {
        });
        printer.onOnline(new GSender(this::capture));
        sent.clear();
        return printer;
    }

    /** Drives the whole chain: the application command, translated, then applied by the aggregate. */
    private List<String> wire(PrinterCommand command) {
        var printer = online();
        PrinterControlService.toMarlin(command).forEach(printer::onCommand);
        return List.copyOf(sent);
    }

    // ---------------------------------------------------------------- homing

    /** A bare {@code G28} is Marlin's "home everything", and it is what naming no axis means. */
    @Test
    void homingNothingInParticularHomesEverything() {
        assertEquals(List.of("G28"), wire(new HomeAxes(null, null, null)));
    }

    @Test
    void homingNamedAxesSendsOnlyThose() {
        assertEquals(List.of("G28 X Z"), wire(new HomeAxes(true, false, true)));
    }

    /**
     * An axis is a flag, not a value.
     *
     * <p>{@code G28 X0} is a legal line that means something subtly different in some firmwares,
     * and the encoder emitting a value here would be the kind of difference nothing notices until
     * a machine homes the wrong way.
     */
    @Test
    void anAxisIsAFlagWithNoValue() {
        var line = wire(new HomeAxes(true, true, true)).getFirst();
        assertEquals("G28 X Y Z", line);
        assertTrue(!line.contains("X0"), line);
    }

    // ---------------------------------------------------------------- moving

    @Test
    void anAbsoluteMoveIsOneCommand() {
        assertEquals(
                List.of("G1 X10 Y20 F3000"),
                wire(new MoveAxes(new BigDecimal("10"), new BigDecimal("20"), null, null,
                        new BigDecimal("3000"), false)));
    }

    /**
     * A relative move is three commands, and the third is the one that matters.
     *
     * <p>{@code G91} is <strong>modal</strong>: the machine stays in relative mode until something
     * says otherwise. Forgetting the {@code G90} would leave every later absolute move - including
     * a print's - interpreted as an offset from wherever the toolhead happened to be.
     */
    @Test
    void aRelativeMoveRestoresAbsoluteModeAfterwards() {
        assertEquals(
                List.of("G91", "G1 X10", "G90"),
                wire(new MoveAxes(new BigDecimal("10"), null, null, null, null, true)));
    }

    @Test
    void aMoveCarriesTheExtruderAndTheFeedrate() {
        assertEquals(
                List.of("G1 Z0.2 E5 F1200"),
                wire(new MoveAxes(null, null, new BigDecimal("0.2"), new BigDecimal("5"),
                        new BigDecimal("1200"), false)));
    }

    // ---------------------------------------------------------------- the fan

    @Test
    void aFanSpeedIsSentOnTheMarlinPwmScale() {
        assertEquals(List.of("M106 S128"), wire(new SetFanSpeed(null, 128)));
    }

    /**
     * The fan index travels in {@code P}.
     *
     * <p>Marlin's documentation lists both {@code I} and {@code P} as "index" and the generated
     * command class carries both, but {@code P} is the one mainline firmware reads as the fan
     * number - and it is what {@code M107} uses, so the pair stays consistent.
     */
    @Test
    void aNamedFanTravelsInP() {
        assertEquals(List.of("M106 S200 P1"), wire(new SetFanSpeed(1, 200)));
    }

    /** Zero is {@code M107}, the documented "off", rather than {@code M106 S0}. */
    @Test
    void zeroSpeedTurnsTheFanOff() {
        assertEquals(List.of("M107"), wire(new SetFanSpeed(null, 0)));
        assertEquals(List.of("M107"), wire(new SetFanSpeed(null, null)));
    }

    // ---------------------------------------------------------------- settings and position

    @Test
    void storingSettingsIsM500() {
        assertEquals(List.of("M500"), wire(new StoreSettings()));
    }

    @Test
    void askingWhereTheToolheadIsIsM114() {
        assertEquals(List.of("M114"), wire(new RequestPosition()));
    }
}
