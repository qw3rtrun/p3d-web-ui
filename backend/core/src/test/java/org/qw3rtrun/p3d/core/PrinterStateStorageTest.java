package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.g.marlin.command.DeleteSDFile;
import org.qw3rtrun.p3d.g.marlin.command.InitSDCard;
import org.qw3rtrun.p3d.g.marlin.command.ListSDCard;
import org.qw3rtrun.p3d.g.marlin.command.ReleaseSDCard;
import org.qw3rtrun.p3d.g.marlin.command.SelectSDFile;
import org.qw3rtrun.p3d.g.marlin.command.StartOrResumeSDPrint;
import org.qw3rtrun.p3d.g.marlin.event.SdCardOk;
import org.qw3rtrun.p3d.g.marlin.event.SdDonePrinting;
import org.qw3rtrun.p3d.g.marlin.event.SdDoneSaving;
import org.qw3rtrun.p3d.g.marlin.event.SdFile;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.g.marlin.event.SdFileOpened;
import org.qw3rtrun.p3d.g.marlin.event.SdFileSelected;
import org.qw3rtrun.p3d.g.marlin.event.SdNotPrinting;
import org.qw3rtrun.p3d.g.marlin.event.SdPrinting;
import org.qw3rtrun.p3d.g.marlin.event.SdWritingToFile;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.terminal.GSender;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the Storage tab's buttons put on the wire, and what the card's answers reach - regression
 * tests for the same feature shipping dead twice: first the commands (each reached
 * {@code onCommand}'s old allow-list, matched no arm, and was dropped, so the tab rendered and
 * routes returned 200 with no byte leaving the host), then the replies ({@code SdCardOk}/
 * {@code SdFileList} decoded correctly and reached {@code onEvent}'s default, which dropped them, so
 * the island timed out reporting "not mounted" about a card that had just mounted).
 * {@code PrinterCommandTranslationTest} passed throughout both, since it only asserts a request body
 * becomes the right {@link GRq} list - neither module can test the join, as
 * {@code :frontend:web-ui} does not depend on {@code :backend:core} by design. Neither default drops
 * any more, so this could not regress the same way a third time; the tests stay because the exact
 * bytes and the exact event were always the point. Writing to a card is deliberately not here: an
 * upload holds the connection from {@code M28} to {@code M29} through {@code PublisherQueue} as an
 * exclusive source and never passes through {@code onCommand} - which is also why it was the one SD
 * operation that worked.
 */
class PrinterStateStorageTest {

    private final List<String> sent = new ArrayList<>();
    private final List<Object> emitted = new ArrayList<>();

    /** A method reference, not a lambda - {@link GSender} has two single-argument constructors. */
    private void capture(String gcode) {
        sent.add(gcode);
    }

    private PrinterState online() {
        PrinterState printer = new PrinterState(UUID.randomUUID());
        printer.setEmitter(emitted::add);
        printer.onOnline(new GSender(this::capture));
        sent.clear();
        emitted.clear();
        return printer;
    }

    // what reached a subscriber, which for a browser means the per-printer SSE stream.
    private List<Object> published(Object... replies) {
        var printer = online();
        for (var reply : replies) {
            printer.onEvent(reply);
        }
        return emitted.stream().filter(e -> !(e instanceof ConnectionStateChanged)).toList();
    }

    // what the aggregate wrote, given the commands PrinterControl.send handed it.
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

    // bare, with no flags: the long form (M20 F, L, T) changes what the firmware writes back, and
    // the listing reader is built for the plain form.
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

    // two commands, both must arrive: select and start are sent as a pair - exactly the shape that
    // half-works if one arm is missing. :gcode's SelectAndStart has carried a filename since #37,
    // so one M32 would also be expressible; which form a print should take is #109.
    @Test
    void printingAFileSelectsItAndThenStarts() {
        assertEquals(List.of("M23 BENCHY.GCO", "M24"),
                wire(new SelectSDFile("BENCHY.GCO"), new StartOrResumeSDPrint()));
    }

    // six commands through one aggregate - the per-command tests above would still pass if
    // something between them cleared the sender.
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

    // ------------------------------------------------------ and what the card says, coming back

    /**
     * The answer to Mount reaches a subscriber - the half still broken after the commands were
     * fixed: {@code M21} went out, {@code echo:SD card ok} came back decoded, and the aggregate
     * dropped it, so the browser timed out reporting "not mounted" about a card that had just
     * mounted. Matters more here than most replies, since the protocol has no query for card
     * presence - the island infers it from this reply and absence from silence, so a dropped
     * {@code SdCardOk} is indistinguishable from an empty slot.
     */
    @Test
    void theAnswerToMountReachesTheBrowser() {
        assertEquals(List.of(SdCardOk.INSTANCE), published(SdCardOk.INSTANCE));
    }

    // the transport assembles Begin/entries/End into one SdFileList - an interrupted listing
    // produces no event rather than a short one - so dropping it left the island unable to tell
    // "the card is empty" from "nobody answered".
    @Test
    void theListingReachesTheBrowserWithItsFilesIntact() {
        var listing = new SdFileList(List.of(
                new SdFile("TEST.GCO", 47L), new SdFile("BENCHY.GCO", 2847561L)));

        assertEquals(List.of(listing), published(listing));

        var delivered = (SdFileList) published(listing).getFirst();
        assertEquals(2, delivered.getFiles().size());
        assertEquals("BENCHY.GCO", delivered.getFiles().get(1).getName());
        assertEquals(2847561L, delivered.getFiles().get(1).getSize());
    }

    // one arm covers the whole SdCardRs family, so a new SD reply needs no change here - asserted as
    // a set, since the claim is about the family and not six replies that happen to work.
    @Test
    void everySdReplyInTheFamilyReachesTheBrowser() {
        var replies = new Object[]{
                SdCardOk.INSTANCE,
                new SdFileOpened("BENCHY.GCO", 2847561L),
                SdFileSelected.INSTANCE,
                new SdWritingToFile("BENCHY.GCO"),
                SdDoneSaving.INSTANCE,
                SdDonePrinting.INSTANCE,
                new SdPrinting(1024L, 2847561L),
                SdNotPrinting.INSTANCE};

        assertEquals(List.of(replies), published(replies));
    }
}
