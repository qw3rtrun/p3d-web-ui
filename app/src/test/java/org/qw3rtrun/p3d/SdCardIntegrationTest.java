package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The card in the machine - issue #43's {@code SdCard} feature, and the one with the worst history.
 *
 * <p>The Storage tab shipped dead twice. First its five buttons answered 200 and sent nothing,
 * because the commands reached {@code PrinterState.onCommand} and fell off a {@code default} arm
 * that dropped them. Then, after that was fixed, its replies reached the host and never reached the
 * browser, for the mirror-image reason. Every layer looked right both times, every test passed, and
 * both took a live run to find.
 *
 * <p>So each test here asserts <em>both</em> ends of one round trip: the command that reached the
 * machine, and the event that reached a browser. Either on its own would have passed throughout the
 * period when the feature did nothing.
 *
 * <p><strong>The configuration axis is whether there is a card.</strong> Marlin has no command that
 * asks; the farm infers it from the machine answering {@code echo:SD card ok} to an {@code M21}.
 * The no-card replies could not be measured - the simulator always has a card - so the no-card
 * tests here assert the <em>absence</em> of {@code SdCardOk} and {@code SdFileList}, which is what
 * the page keys on, and never the text of whatever else the machine says.
 */
class SdCardIntegrationTest extends FarmIntegrationTest {

    /** Mounting a card that is there is announced to the browser as {@code SdCardOk}. */
    @Test
    void mountingAnnouncesTheCardWhenThereIsOne() {
        var printer = machine(MockPrinter.marlin());
        var id = register("has a card", printer);
        var events = watch(id);
        connectAndWait(id);
        printer.forget();

        command(id, "/sd/mount").expectStatus().isOk();

        await("M21 to reach the machine", () -> printer.sent("M21"));
        events.await("SdCardOk");
    }

    /**
     * Mounting when there is no card announces no card, and that is the whole signal.
     *
     * <p>The machine still answers something and the host still publishes whatever it decoded -
     * here an {@code EchoMessage} - but nothing claims to be a card. The page shows its empty state
     * because {@code SdCardOk} did not arrive, not because anything said "no".
     */
    @Test
    void mountingAnnouncesNoCardWhenThereIsNone() {
        var printer = machine(MockPrinter.withoutCard());
        var id = register("empty reader", printer);
        var events = watch(id);
        connectAndWait(id);
        printer.forget();

        command(id, "/sd/mount").expectStatus().isOk();

        await("M21 to reach the machine", () -> printer.sent("M21"));
        quiet();
        assertFalse(events.saw("SdCardOk"),
                "there is no card, so nothing may tell the browser there is one: " + events.types());
    }

    /**
     * A listing arrives as <strong>one</strong> message, whatever it took on the wire.
     *
     * <p>{@code M20}'s answer is {@code Begin file list}, an entry per file, {@code End file list}.
     * The entries are not claimable replies - {@code TEST.GCO 47} is {@code <word> <number>}, far
     * too common a shape for a decoder to claim - so they reach the transport as lines nothing
     * wanted, and before {@code SdFileListReader} existed they were dropped and no file list could
     * ever reach a browser at all. This is that assembly, end to end.
     */
    @Test
    void theCardsContentsReachTheBrowserAsOneMessage() {
        var printer = machine(MockPrinter.marlin())
                .withFile("TEST.GCO", 47)
                .withFile("BENCHY.GCO", 1245678);
        var id = register("listed", printer);
        var events = watch(id);
        connectAndWait(id);
        printer.forget();

        command(id, "/sd/list").expectStatus().isOk();

        await("M20 to reach the machine", () -> printer.sent("M20"));
        var listing = events.await("SdFileList");

        assertEquals(1, events.types().stream().filter("SdFileList"::equals).count(),
                "three or more lines on the wire are one listing to a browser");
        assertEquals(List.of("TEST.GCO", "BENCHY.GCO"), names(listing));
        assertEquals(47, ((Map<?, ?>) files(listing).getFirst()).get("size"));
    }

    /** An empty card is a listing with nothing in it, not the absence of a listing. */
    @Test
    void anEmptyCardListsAsAnEmptyList() {
        var printer = machine(MockPrinter.marlin());
        var id = register("empty card", printer);
        var events = watch(id);
        connectAndWait(id);

        command(id, "/sd/list").expectStatus().isOk();

        var listing = events.await("SdFileList");
        assertEquals(List.of(), names(listing));
    }

    /** With no card there is no listing to send, so the browser is told nothing. */
    @Test
    void thereIsNoListingWithoutACard() {
        var printer = machine(MockPrinter.withoutCard());
        var id = register("no card to list", printer);
        var events = watch(id);
        connectAndWait(id);
        printer.forget();

        command(id, "/sd/list").expectStatus().isOk();

        await("M20 to reach the machine", () -> printer.sent("M20"));
        quiet();
        assertFalse(events.saw("SdFileList"),
                "a machine with no card must not appear to have an empty one: " + events.types());
    }

    /**
     * Printing a file is two commands sent as one unit, in order.
     *
     * <p>{@code M23} selects and {@code M24} starts, because {@code :gcode}'s {@code M32} cannot
     * carry a filename. The order is the whole of it - an {@code M24} in front of its
     * {@code M23} starts whatever was selected last, which is somebody else's print.
     */
    @Test
    void printingAFileSelectsItAndThenStartsIt() {
        var printer = machine(MockPrinter.marlin()).withFile("TEST.GCO", 47);
        var id = register("printing", printer);
        var events = watch(id);
        connectAndWait(id);
        printer.forget();

        command(id, "/sd/print", "{\"name\":\"TEST.GCO\"}").expectStatus().isOk();

        await("both commands to reach the machine", () -> printer.sent("M24"));
        assertEquals(List.of("M23 TEST.GCO", "M24"), printer.commands(),
                "select then start, and nothing between them");

        var opened = events.await("SdFileOpened");
        assertEquals("TEST.GCO", opened.get("file"));
        assertEquals(47, opened.get("size"));
        events.await("SdFileSelected");
    }

    /**
     * Deleting a file reaches the machine, and the card's contents change.
     *
     * <p>Asserted by listing the card again rather than by trusting the delete: Marlin does not
     * confirm an {@code M30} in a form anything decodes, so "it was deleted" can only honestly mean
     * "it is no longer there".
     */
    @Test
    void deletingAFileTakesItOffTheCard() {
        var printer = machine(MockPrinter.marlin())
                .withFile("KEEP.GCO", 100)
                .withFile("DROP.GCO", 200);
        var id = register("deleting", printer);
        var events = watch(id);
        connectAndWait(id);
        printer.forget();

        command(id, "/sd/delete", "{\"name\":\"DROP.GCO\"}").expectStatus().isOk();
        await("M30 to reach the machine", () -> printer.sent("M30 DROP.GCO"));

        command(id, "/sd/list").expectStatus().isOk();

        await("a listing without the deleted file",
                () -> names(events.payloadOf("SdFileList")).equals(List.of("KEEP.GCO")));
    }

    /**
     * A filename that could break the wire is refused at the edge, with a 400.
     *
     * <p>A name reaches the printer verbatim, so a {@code ;} would comment out the rest of the
     * block and a {@code *} would look like the start of a checksum. Refusing it here is cheaper
     * than discovering it as a line of nonsense on a card.
     */
    @Test
    void aFilenameThatWouldCorruptTheBlockIsRefused() {
        var printer = machine(MockPrinter.marlin());
        var id = register("validating", printer);
        connectAndWait(id);
        printer.forget();

        command(id, "/sd/delete", "{\"name\":\"BAD;NAME.GCO\"}").expectStatus().isBadRequest();

        quiet();
        assertFalse(printer.sent("M30"), "a refused name must not reach the machine");
    }

    /** Releasing the card is {@code M22}, and it goes out as asked. */
    @Test
    void releasingTheCardReachesTheMachine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("releasing", printer);
        connectAndWait(id);
        printer.forget();

        command(id, "/sd/release").expectStatus().isOk();

        await("M22 to reach the machine", () -> printer.sent("M22"));
    }

    // ---------------------------------------------------------------- reading the listing payload

    @SuppressWarnings("unchecked")
    private static List<Object> files(Map<String, Object> listing) {
        return listing == null ? List.of() : (List<Object>) listing.get("files");
    }

    private static List<String> names(Map<String, Object> listing) {
        return files(listing).stream().map(f -> (String) ((Map<?, ?>) f).get("name")).toList();
    }

    /** A pause used only where the claim is that something did not arrive. */
    private static void quiet() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
