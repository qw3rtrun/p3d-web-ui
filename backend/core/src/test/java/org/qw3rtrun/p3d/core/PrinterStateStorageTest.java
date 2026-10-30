package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.command.DeleteSDFile;
import org.qw3rtrun.p3d.g.marlin.command.InitSDCard;
import org.qw3rtrun.p3d.g.marlin.command.ListSDCard;
import org.qw3rtrun.p3d.g.marlin.command.ReleaseSDCard;
import org.qw3rtrun.p3d.g.marlin.command.SelectSDFile;
import org.qw3rtrun.p3d.g.marlin.command.StartOrResumeSDPrint;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.terminal.GSender;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the Storage tab's buttons actually put on the wire.
 *
 * <p><strong>This is the half of a pair that was missing, and the gap shipped a dead feature.</strong>
 * {@code PrinterCommandTranslationTest} asserts that a request body becomes the right {@link GRq}
 * list and it passed throughout; nothing asserted that the aggregate then <em>sends</em> it. Every
 * one of these commands reached {@code onCommand}'s default arm and was logged and dropped, so the
 * tab rendered, the requests bound, the routes returned 200, and no byte left the host.
 *
 * <p>{@code PrinterStateMotionTest} says the same thing about the same switch one feature earlier -
 * <em>"the state this suite once found the code in, with the translation working perfectly and
 * nothing arriving at the machine"</em>. This is that, a second time, which is why these assert the
 * bytes rather than that a handler was reached: the object graph is not what a printer reads, and a
 * command that is routed but not written is indistinguishable from one that works until someone
 * watches a machine do nothing.
 *
 * <p><strong>Writing to a card is deliberately not here.</strong> An upload is a stream that has to
 * hold the connection from {@code M28} to {@code M29}, so it goes to {@code PublisherQueue} as an
 * exclusive source and never passes through {@code onCommand} at all. That is also why it was the
 * one SD operation that worked: it is the one that does not use this path.
 */
class PrinterStateStorageTest {

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

    // ---------------------------------------------------------------- the card itself

    /** Mount. The reported symptom was this exact command being dropped. */
    @Test
    void mountingACardSendsM21() {
        assertEquals(List.of("M21"), wire(new InitSDCard()));
    }

    @Test
    void releasingACardSendsM22() {
        assertEquals(List.of("M22"), wire(new ReleaseSDCard()));
    }

    /**
     * A bare {@code M20}, with none of the flags the command can carry.
     *
     * <p>The long form ({@code M20 F}, {@code L}, {@code T}) changes what the firmware writes back,
     * and the reader this host assembles a listing with is built for the plain form. Asserted as
     * the exact string for that reason rather than as a prefix.
     */
    @Test
    void listingACardSendsABareM20() {
        assertEquals(List.of("M20"), wire(new ListSDCard()));
    }

    // ---------------------------------------------------------------- files on it

    /** The name is the rest of the line, unquoted - which is what {@code M30} takes. */
    @Test
    void deletingAFileSendsM30AndTheName() {
        assertEquals(List.of("M30 BENCHY.GCO"), wire(new DeleteSDFile("BENCHY.GCO")));
    }

    /**
     * Printing is two commands, and both have to arrive.
     *
     * <p>{@code M32} would do it in one and is what #32 asked for, but {@code :gcode}'s generated
     * {@code SelectAndStart} carries no filename - see #37. Until it does, select and start are
     * sent as a pair, and a pair is exactly the shape that half-works if one arm is missing from
     * the dispatch: a file selected and never started looks like a machine ignoring the button.
     */
    @Test
    void printingAFileSelectsItAndThenStarts() {
        assertEquals(List.of("M23 BENCHY.GCO", "M24"),
                wire(new SelectSDFile("BENCHY.GCO"), new StartOrResumeSDPrint()));
    }

    /**
     * The whole tab in the order a session uses it, as one sequence.
     *
     * <p>Six commands through one aggregate, because the per-command tests above would all still
     * pass if the switch handled each in isolation and something between them cleared the sender.
     * This is the sequence {@code SdUploadProbe} runs by hand, minus the write.
     */
    @Test
    void aWholeSessionAtTheCardReachesTheWireInOrder() {
        assertEquals(
                List.of("M21", "M20", "M23 BENCHY.GCO", "M24", "M30 OLD.GCO", "M22"),
                wire(new InitSDCard(),
                        new ListSDCard(),
                        new SelectSDFile("BENCHY.GCO"),
                        new StartOrResumeSDPrint(),
                        new DeleteSDFile("OLD.GCO"),
                        new ReleaseSDCard()));
    }
}
