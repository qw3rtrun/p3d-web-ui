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
 * What the Storage tab's buttons put on the wire, and what the card's answers reach.
 *
 * <p><strong>Both halves of this file are regression tests for the same feature shipping dead
 * twice.</strong> The commands went first: each reached {@code onCommand}'s old allow-list, matched
 * no arm, and was logged and dropped, so the tab rendered, the requests bound, the routes returned
 * 200 and no byte left the host. With that fixed the tab still did nothing, because
 * {@code SdCardOk} and {@code SdFileList} came back, decoded correctly, and reached
 * {@code onEvent}'s default, which dropped them - so the island timed out and reported "not
 * mounted" about a card that had just mounted.
 *
 * <p>{@code PrinterCommandTranslationTest} passed throughout both. It asserts a request body
 * becomes the right {@link GRq} list, which says nothing about whether the aggregate sends it, and
 * nothing at all about the reply. <strong>Neither module can test the join</strong>: {@code :frontend:web-ui}
 * does not depend on {@code :backend:core} by design, so the two halves are asserted either side
 * of a seam no test spans.
 *
 * <p>Neither default drops any more, so none of this could regress the same way a third time. The
 * tests stay because what they assert is the thing that was always the point - the exact bytes, and
 * the exact event - and both are still worth pinning: {@code M20} must stay bare because the
 * listing reader is built for the plain form, printing must stay an ordered pair, and the listing
 * has to arrive whole or the page cannot tell an empty card from an unanswered question.
 *
 * <p><strong>Writing to a card is deliberately not here.</strong> An upload is a stream that has to
 * hold the connection from {@code M28} to {@code M29}, so it goes to {@code PublisherQueue} as an
 * exclusive source and never passes through {@code onCommand} at all. That is also why it was the
 * one SD operation that worked: it is the one that does not use this path.
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

    /** What reached a subscriber, which for a browser means the per-printer SSE stream. */
    private List<Object> published(Object... replies) {
        var printer = online();
        for (var reply : replies) {
            printer.onEvent(reply);
        }
        return emitted.stream().filter(e -> !(e instanceof ConnectionStateChanged)).toList();
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

    // ------------------------------------------------------ and what the card says, coming back

    /**
     * The answer to Mount reaches a subscriber.
     *
     * <p><strong>This is the half that was still broken after the commands were fixed.</strong>
     * {@code M21} went out, {@code echo:SD card ok} came back, {@code :gcode} decoded it, and the
     * aggregate dropped it - so the browser saw nothing, timed out after four seconds and reported
     * "not mounted" about a card that had just mounted successfully.
     *
     * <p>It matters more here than for most replies because <strong>the protocol has no way to ask
     * whether a card is present</strong>. There is no query, so the island infers presence from
     * this reply and absence from silence; a dropped {@code SdCardOk} is indistinguishable from an
     * empty slot.
     */
    @Test
    void theAnswerToMountReachesTheBrowser() {
        assertEquals(List.of(SdCardOk.INSTANCE), published(SdCardOk.INSTANCE));
    }

    /**
     * So does the listing, whole.
     *
     * <p>The transport assembles {@code Begin file list} / entries / {@code End file list} into one
     * {@code SdFileList} - an interrupted listing produces no event at all rather than a short one -
     * so this is the single event the table is drawn from. Dropping it left the island unable to
     * tell "the card is empty" from "nobody answered", which are different sentences on the page.
     */
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

    /**
     * One arm covers the family, which is why a new SD reply needs no change here.
     *
     * <p>Every one of these implements {@code SdCardRs}, so {@code case SdCardRs<?>} routes them
     * all - the same rule the transport follows for {@code OkRs} and {@code BusyRs}. Asserted as a
     * set rather than one at a time because the claim is about the family, not about six replies
     * that happen to work.
     */
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
