package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.command.AbsolutePositioning;
import org.qw3rtrun.p3d.g.marlin.command.AutoHome;
import org.qw3rtrun.p3d.g.marlin.command.FanOff;
import org.qw3rtrun.p3d.g.marlin.command.GetCurrentPosition;
import org.qw3rtrun.p3d.g.marlin.command.LinearMoveG1;
import org.qw3rtrun.p3d.g.marlin.command.RelativePositioning;
import org.qw3rtrun.p3d.g.marlin.command.SaveSettings;
import org.qw3rtrun.p3d.g.marlin.command.SetFanSpeed;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.terminal.GSender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The commands a Marlin screen offers, as the bytes the aggregate puts on the wire.
 *
 * <p>These assert the <strong>wire format</strong> and not the object graph, because the object
 * graph is not what a printer reads.
 *
 * <p><strong>What this half used to guard was {@code onCommand}'s default arm</strong>, back when
 * that arm dropped anything the aggregate had not been taught to send. It guarded it badly: the
 * list of arms and the list of tests were both written by hand and both by the same act of
 * remembering, so they failed together. Motion shipped dead once and every command on the Storage
 * tab shipped dead after it, each with its translation passing. The default now passes a command
 * through, and what the switch names is the two kinds that are not ordinary - decided, and refused.
 *
 * <p>These tests are kept, and are worth more than they were. They no longer stand between a
 * command and oblivion, so what they assert is the thing that was always the point: the exact bytes
 * a Marlin screen's buttons put on the wire, which is the half of the contract no type checker sees.
 *
 * <p>The other half - that a request body becomes these commands - is
 * {@code PrinterCommandTranslationTest} in {@code :frontend:web-ui}, which is where the request
 * bodies live now. The two were one test while {@code api} carried a command model of its own and
 * {@code core} translated it; they are split where the models were merged, and <strong>no test can
 * join them</strong>: {@code :frontend:web-ui} does not depend on {@code :backend:core} by design,
 * so neither module can name both sides of that seam. Which is the other half of why the allow-list
 * could not hold.
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

    /** What the aggregate wrote, given the commands {@code PrinterControl.send} handed it. */
    private List<String> wire(GRq<?>... commands) {
        var printer = online();
        for (var command : commands) {
            printer.onCommand(command);
        }
        return List.copyOf(sent);
    }

    // ---------------------------------------------------------------- homing

    /** A bare {@code G28} is Marlin's "home everything". */
    @Test
    void aBareAutoHomeHomesEverything() {
        assertEquals(List.of("G28"), wire(new AutoHome()));
    }

    @Test
    void namedAxesAreSentAsFlags() {
        assertEquals(List.of("G28 X Z"), wire(new AutoHome(
                false, null, false, null,
                true, false, true,
                false, false, false, false, false, false)));
    }

    // ---------------------------------------------------------------- moving

    @Test
    void anAbsoluteMoveIsOneCommand() {
        assertEquals(List.of("G1 X10 Y20 F3000"), wire(new LinearMoveG1(
                new BigDecimal("10"), new BigDecimal("20"), null, null, null, null, null, null, null,
                null, new BigDecimal("3000"), null)));
    }

    /**
     * A relative move is three commands, and all three reach the wire.
     *
     * <p>{@code G91} is <strong>modal</strong>: the machine stays in relative mode until something
     * says otherwise. The aggregate dropping the {@code G90} on an unlisted arm would leave every
     * later absolute move - including a print's - interpreted as an offset.
     */
    @Test
    void aRelativeMoveReachesTheWireWholeAndInOrder() {
        assertEquals(
                List.of("G91", "G1 X10", "G90"),
                wire(new RelativePositioning(),
                        new LinearMoveG1(new BigDecimal("10"), null, null, null, null, null, null,
                                null, null, null, null, null),
                        new AbsolutePositioning()));
    }

    @Test
    void aMoveCarriesTheExtruderAndTheFeedrate() {
        assertEquals(List.of("G1 Z0.2 E5 F1200"), wire(new LinearMoveG1(
                null, null, new BigDecimal("0.2"), null, null, null, null, null, null,
                new BigDecimal("5"), new BigDecimal("1200"), null)));
    }

    // --------------------------------------------------------------- the fan

    @Test
    void aFanSpeedIsSentOnTheMarlinPwmScale() {
        assertEquals(List.of("M106 S128"), wire(new SetFanSpeed(null, 128, null, null)));
    }

    @Test
    void aNamedFanTravelsInP() {
        assertEquals(List.of("M106 S200 P1"), wire(new SetFanSpeed(null, 200, 1, null)));
    }

    @Test
    void theFanIsTurnedOffWithM107() {
        assertEquals(List.of("M107"), wire(new FanOff(null)));
    }

    // ------------------------------------------------- settings and position

    @Test
    void storingSettingsIsM500() {
        assertEquals(List.of("M500"), wire(new SaveSettings()));
    }

    @Test
    void askingWhereTheToolheadIsIsM114() {
        assertEquals(List.of("M114"), wire(new GetCurrentPosition(false, false, false)));
    }
}
