package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 *
 * <h2>Writing a file to the card - issue #53</h2>
 *
 * <p>The second half of this class, and the only feature here that is not a round trip. An upload
 * is a whole file streamed through the send window a block at a time, and it is the one thing on
 * this machine that <strong>takes the wire away from everything else</strong>: between {@code M28}
 * and {@code M29} the firmware stores what it is sent instead of running it, and acknowledges each
 * line exactly as if it had run it. Nothing on either end reports the difference.
 *
 * <p>So these tests have a <em>third</em> observable, and it is at the same boundary as the other
 * two: {@link MockPrinter#written(String)}, the file on the card. The whole claim of the feature is
 * that it differs from {@link MockPrinter#commands()} - the traffic carried the transfer and one
 * interactive command, and only the transfer is in the file.
 *
 * <p>Nothing here is timed. Every mid-transfer assertion stops the machine answering with
 * {@link MockPrinter#holdRepliesFrom(String)} and lets the send window stop the conversation, the
 * same mechanism {@code PrintJobIntegrationTest} uses and for the same reason: against a mock that
 * answers instantly a transfer is over before the test's next line runs.
 */
class SdCardIntegrationTest extends FarmIntegrationTest {

    /** Mounting a card that is there is announced to the browser as {@code SdCardOk}. */
    @Test
    void mountingAnnouncesTheCardWhenThereIsOne() {
        var printer = machine(MockPrinter.marlin());
        var id = register("has a card", printer);
        var events = connectAndSettle(id, printer);

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
        var events = connectAndSettle(id, printer);

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
        var events = connectAndSettle(id, printer);

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
        var events = connectAndSettle(id, printer);

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
        var events = connectAndSettle(id, printer);

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
        var events = connectAndSettle(id, printer);

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
        connectAndSettle(id, printer);

        command(id, "/sd/delete", "{\"name\":\"BAD;NAME.GCO\"}").expectStatus().isBadRequest();

        quiet();
        assertFalse(printer.sent("M30"), "a refused name must not reach the machine");
    }

    /** Releasing the card is {@code M22}, and it goes out as asked. */
    @Test
    void releasingTheCardReachesTheMachine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("releasing", printer);
        connectAndSettle(id, printer);

        command(id, "/sd/release").expectStatus().isOk();

        await("M22 to reach the machine", () -> printer.sent("M22"));
    }

    // ---------------------------------------------------------------- writing a file to the card

    /**
     * A file the farm holds goes onto the card block for block, and the card ends up holding it.
     *
     * <p><strong>Two lists, and the whole feature is that they differ.</strong> What reached the
     * machine is {@code M28}, the file, {@code M29} - asserted whole, per §3.3, because the unit is
     * more than one command and a missing {@code M29} is a file left open rather than a visible
     * failure. What is <em>on the card</em> is the file alone, because everything between the two
     * was stored rather than run.
     */
    @Test
    void writingAFileToTheCardPutsEveryBlockOfItInTheFile() {
        theWholeFileReachesTheCard(MockPrinter.marlin(), "sd-upload");
    }

    /**
     * The same, against a firmware that acknowledges with a bare {@code ok}.
     *
     * <p><strong>The acknowledgement dialect is the configuration axis a transfer has</strong>, for
     * the reason a print job has it: every line is a round trip through the send window, and a
     * machine reporting no buffer counts holds that window at one block where {@code ok P15 B3}
     * opens it to three. "The file arrives either way" is a real claim, and the file that comes out
     * has to be identical.
     *
     * <p><strong>{@link MockPrinter#withoutCard()} is deliberately not driven</strong>, and that is
     * §4.2 being obeyed rather than a gap: the simulator always has a card, so what a machine
     * without one answers an {@code M28} has never been measured. Inventing it and then asserting
     * on it would be a test of the invention. {@code dualExtruder()} changes nothing - a transfer
     * consults no extruder count.
     */
    @Test
    void aFileReachesTheCardIdenticallyOnAFirmwareThatReportsNoBufferCounts() {
        theWholeFileReachesTheCard(MockPrinter.legacyOk(), "sd-upload-legacy");
    }

    private void theWholeFileReachesTheCard(MockPrinter.Profile profile, String mark) {
        var printer = machine(profile);
        var id = register(profile.label() + " taking a file", printer);
        var file = GcodeFixture.unsliced(mark).named(mark + ".gcode").withBodyLines(6);
        var events = connectAndSettle(id, printer);

        writeToCard(id, file, "UPLOAD.GCO");

        var done = awaitTransfer(events, "DONE");
        closed(printer, "UPLOAD.GCO");

        var expected = new ArrayList<String>();
        expected.add("M28 UPLOAD.GCO");
        expected.addAll(file.blocks());
        expected.add("M29");
        assertEquals(expected, printer.commands(),
                "open the file, write it, close it - and nothing else on the wire");

        assertEquals(file.blocks(), printer.written("UPLOAD.GCO"),
                "what is on the card has to be the file and not the traffic that carried it");

        assertEquals("UPLOAD.GCO", done.get("file"), "the name the card knows it by");
        assertEquals(file.sizeBytes(), number(done.get("bytesTotal")),
                "the denominator a progress figure is divided by");
        assertEquals((long) file.blocks().size(), number(done.get("blocksSent")));

        // The reply half. Both of these are decoded types that nothing in this suite had ever
        // exercised end to end before - see the measurement quoted in MockPrinter's M28 arm.
        assertEquals("UPLOAD.GCO", events.await("SdWritingToFile").get("file"),
                "the machine's own confirmation of which file it opened never reached the browser");
        events.await("SdDoneSaving");
    }

    /**
     * The request is answered while the transfer is still going, and not when it has finished.
     *
     * <p>Every line of a transfer is a round trip inside the send window, so a real file takes
     * minutes and a request held open for one is a request that times out.
     *
     * <p><strong>Proved by stopping the machine answering rather than by timing anything.</strong>
     * From {@code holdRepliesFrom} onwards the transfer <em>cannot</em> finish - the send window
     * will not pull another block until one comes back - so a 200 while the file is still open on
     * the card is the claim, stated in a way no loaded runner can turn into a race.
     */
    @Test
    void theRequestIsAnsweredWhenTheTransferStartsAndNotWhenItEnds() {
        var printer = machine(MockPrinter.marlin());
        var id = register("answers early", printer);
        var file = GcodeFixture.unsliced("sd-upload-early").named("early.gcode").withBodyLines(24);
        var blocks = file.blocks();
        printer.holdRepliesFrom(blocks.get(5));
        var events = connectAndSettle(id, printer);

        writeToCard(id, file, "EARLY.GCO");

        var onTheWire = stalled(printer, blocks.get(5));

        assertFalse(printer.sent("M29"),
                "the transfer finished, so the request proves nothing: " + printer.commands());
        assertEquals("EARLY.GCO", printer.writingTo(),
                "the request was answered, and the file is still open on the card");
        assertTrue(onTheWire < blocks.size(),
                "the whole file went out before the machine stopped answering, so there was never"
                        + " anything in flight to answer early about: " + printer.commands());
        assertNotNull(transferIn(events, "UPLOADING"),
                "a page has to be told a transfer is running before it can draw one: "
                        + transferStates(events));

        printer.answerFreely();
        awaitTransfer(events, "DONE");
    }

    /**
     * Nothing else reaches the machine while a file is going onto the card.
     *
     * <p><strong>This is the one with teeth.</strong> {@code M28} turns the wire into a file sink,
     * so a command from any other source that lands between it and {@code M29} is written into the
     * G-code rather than executed - and it is <em>acknowledged exactly as if it had run</em>, so
     * nothing on either end reports it. The fault surfaces hours later as a line of nonsense
     * mid-print. {@code SdUploadProbe} measured it against MarlinSim as 675 bytes against 687, with
     * exclusivity and without; this is the same claim at the level of the whole assembly.
     *
     * <p>The interactive command is posted while the machine is not answering, so it is in the
     * priority queue before anything can be released - a 200 from {@code /report-temp} means the
     * {@code M105} has already been applied on the aggregate's executor (§4.4). What happens to it
     * next is the assertion: it must wait for {@code M29} and must not appear in the file.
     *
     * <h2>What this proves, and what it does not</h2>
     *
     * <p><strong>It is the priority that is being proved here, not the exclusivity</strong>, and
     * that was established by mutation rather than by reading: replacing
     * {@code addExclusivePublisher} with {@code addPublisher} in {@code PrinterReactor.uploadToCard}
     * leaves this test green, and demoting the transfer from {@code REALTIME} to {@code BACKGROUND}
     * fails it. Both are what the two mechanisms are: priority decides who gets the <em>next</em>
     * unit of demand, and exclusivity covers the case priority cannot - a source that was handed
     * demand <em>before</em> the transfer was queued and is still holding it.
     *
     * <p>That case cannot be produced from out here, and the reason is a property of the
     * application rather than a gap in this suite: every source this farm has is request-driven and
     * spends what it is given at once, so no endpoint can make one sit on unspent demand. Producing
     * it takes a deliberately slow publisher, which is what {@code SdUploadProbe}'s
     * {@code SlowSource} is - 675 bytes against 687 - and what
     * {@code PublisherQueueExclusivityTest} builds in {@code :backend:terminal}. Exclusivity is
     * therefore belt over braces today, and this is the braces.
     */
    @Test
    void theMachineTakesNothingElseWhileAFileIsGoingOntoTheCard() {
        var printer = machine(MockPrinter.marlin());
        var id = register("exclusive", printer);
        var file = GcodeFixture.unsliced("sd-upload-excl").named("excl.gcode").withBodyLines(24);
        var blocks = file.blocks();
        printer.holdRepliesFrom(blocks.get(5));
        var events = connectAndSettle(id, printer);

        writeToCard(id, file, "EXCL.GCO");
        stalled(printer, blocks.get(5));

        command(id, "/report-temp").expectStatus().isOk();
        printer.answerFreely();

        awaitTransfer(events, "DONE");
        closed(printer, "EXCL.GCO");
        await(() -> "M105 to reach the machine after the transfer. It received "
                + printer.commands(), () -> printer.sent("M105"));

        assertEquals(blocks, printer.written("EXCL.GCO"),
                "an interactive command sent mid-transfer was written into the file instead of"
                        + " being run, which is the corruption exclusivity exists to prevent");

        var received = printer.commands();
        assertTrue(received.indexOf("M105") > received.indexOf("M29"),
                "M105 reached the wire before the file was closed: " + received);
    }

    /**
     * Cancelling is a stop and not a cancel: {@code M29} still goes out, so the file is closed.
     *
     * <p><strong>The difference is the whole of why this is worth a test.</strong> Tearing the
     * transfer out would end the exclusive hold with the file still open on the card, and the next
     * command the machine was given would be written into it - the corruption the feature exists to
     * prevent, caused by the button meant to stop it. So the claim has two halves and the second is
     * the one with teeth: the card holds a short but <em>closed</em> file, and the very next
     * interactive command is <em>run</em> rather than stored.
     */
    @Test
    void cancellingATransferStopsItAndStillClosesTheFile() {
        var printer = machine(MockPrinter.marlin());
        var id = register("stopped mid-transfer", printer);
        var file = GcodeFixture.unsliced("sd-upload-stop").named("stop.gcode").withBodyLines(40);
        var blocks = file.blocks();
        printer.holdRepliesFrom(blocks.get(5));
        var events = connectAndSettle(id, printer);

        writeToCard(id, file, "STOPPED.GCO");
        stalled(printer, blocks.get(5));

        command(id, "/sd/upload/cancel").expectStatus().isOk();
        printer.answerFreely();

        var cancelled = awaitTransfer(events, "CANCELLED");
        closed(printer, "STOPPED.GCO");
        events.await("SdDoneSaving");

        var onCard = printer.written("STOPPED.GCO");
        assertFalse(onCard.isEmpty(), "nothing was written at all, so nothing was stopped");
        assertTrue(onCard.size() < blocks.size(),
                "the whole file went onto the card anyway - the cancel only stopped it on paper: "
                        + onCard.size() + " of " + blocks.size() + " blocks");
        assertEquals(blocks.subList(0, onCard.size()), onCard,
                "what did get written has to be the start of the file, in order");
        assertEquals("M29", printer.commands().getLast(),
                "the close has to be the last thing on the wire: " + printer.commands());
        assertEquals((long) onCard.size(), number(cancelled.get("blocksSent")),
                "the page is told how much got there, and it has to be how much got there");

        // The teeth. The file is closed, so the next command is executed; if the transfer had been
        // torn out instead it would have been stored, acknowledged, and invisible from here.
        command(id, "/report-temp").expectStatus().isOk();
        await(() -> "M105 to reach the machine. It received " + printer.commands(),
                () -> printer.sent("M105"));
        quiet();

        assertEquals(onCard, printer.written("STOPPED.GCO"),
                "the next command after a cancel was written into the file, so the file was left"
                        + " open: " + printer.written("STOPPED.GCO"));
    }

    /**
     * A name {@code M20} would not list gets an extension, so the file can be found again.
     *
     * <p>Measured rather than read: a probe wrote a file called {@code test}, got an empty listing
     * back, and the first conclusion drawn was that the simulator was broken. It was not - Marlin
     * lists only {@code .gcode}, {@code .gco} and {@code .g}, and everything else is on the card,
     * prints by exact name, and is invisible to every listing and therefore undeletable from a page.
     *
     * <p>Asserted by listing the card, which is the only honest way to say "it can be found".
     */
    @Test
    void aNameTheCardWouldNotListGetsAnExtension() {
        var printer = machine(MockPrinter.marlin());
        var id = register("naming", printer);
        var file = GcodeFixture.unsliced("sd-upload-name").named("name.gcode").withBodyLines(2);
        var events = connectAndSettle(id, printer);

        writeToCard(id, file, "BENCHY");

        var done = awaitTransfer(events, "DONE");
        closed(printer, "BENCHY.gco");

        assertEquals("BENCHY.gco", done.get("file"),
                "the page shows what is really on the card, not what was typed");
        assertEquals("M28 BENCHY.gco", printer.commands().getFirst(),
                "the extension has to be on the wire, not only in the report");

        command(id, "/sd/list").expectStatus().isOk();
        await(() -> "a listing containing the uploaded file. The card listed "
                        + names(events.payloadOf("SdFileList")),
                () -> names(events.payloadOf("SdFileList")).contains("BENCHY.gco"));
    }

    /**
     * A card name that could break the wire is refused, and no file is opened.
     *
     * <p>The same validator as {@code /sd/delete}, over a different request shape - and a worse
     * consequence if it let one through. A {@code ;} in the name of a file being <em>deleted</em>
     * costs a rejected block; in an {@code M28} it opens a file whose name is half of what was
     * typed, and every line of the transfer goes into it.
     */
    @Test
    void aCardNameThatWouldCorruptTheBlockIsRefusedBeforeAnyFileIsOpened() {
        var printer = machine(MockPrinter.marlin());
        var id = register("validating an upload", printer);
        connectAndSettle(id, printer);
        var stored = idOf(upload(GcodeFixture.unsliced("sd-upload-bad").named("bad.gcode")));

        command(id, "/sd/upload", "{\"fileId\":\"" + stored + "\",\"name\":\"BAD;NAME.GCO\"}")
                .expectStatus().isBadRequest();

        quiet();
        assertFalse(printer.sent("M28"), "a refused name must not open a file on the card");
        assertNull(printer.writingTo());
    }

    // ---------------------------------------------------------------- driving one transfer

    /** Puts a file in the store and asks the machine to write it to its card, as a browser does. */
    private void writeToCard(UUID id, GcodeFixture file, String cardName) {
        var stored = idOf(upload(file));
        command(id, "/sd/upload",
                "{\"fileId\":\"" + stored + "\",\"name\":\"" + cardName + "\"}")
                .expectStatus().isOk();
    }

    /**
     * Waits until the file is shut on the card.
     *
     * <p><strong>Not the same moment as the {@code SdUploadProgressed} that reports the end.</strong>
     * That one is emitted when the <em>source</em> is exhausted - when {@code M29} has been handed to
     * the queue - and the block still has to be written, read and stored at the far end. Waiting on
     * the state alone was a race, and it would have been a race that passed on a developer's machine
     * every time.
     */
    private static void closed(MockPrinter printer, String cardName) {
        await(() -> "the file on the card to be closed. The machine received " + printer.commands()
                        + " and is writing to " + printer.writingTo(),
                () -> printer.sent("M29") && printer.writingTo() == null);
        assertFalse(printer.written(cardName).isEmpty(),
                "a file was closed on the card and it was not " + cardName
                        + ". The machine received " + printer.commands());
    }

    /** Waits for an {@code SdUploadProgressed} in this state, and hands back its payload. */
    private Map<String, Object> awaitTransfer(Events events, String state) {
        await(() -> "an SdUploadProgressed in state " + state
                        + ". The stream carried " + transferStates(events),
                () -> transferIn(events, state) != null);
        return transferIn(events, state);
    }

    /**
     * The first transfer report in this state, or null.
     *
     * <p>Not {@code Events.payloadOf}, which answers with the most recent of a type: a transfer
     * emits several {@code SdUploadProgressed} and only the last of them is terminal, so "did it
     * ever say UPLOADING" and "did it end DONE" are two different questions about the same type.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> transferIn(Events events, String state) {
        for (var message : events.all()) {
            if ("SdUploadProgressed".equals(message.get("type"))) {
                var event = (Map<String, Object>) message.get("event");
                if (state.equals(event.get("state"))) {
                    return event;
                }
            }
        }
        return null;
    }

    /** Every state the stream reported, in order - the evidence a failure here needs. */
    @SuppressWarnings("unchecked")
    private static List<String> transferStates(Events events) {
        return events.all().stream()
                .filter(m -> "SdUploadProgressed".equals(m.get("type")))
                .map(m -> (String) ((Map<String, Object>) m.get("event")).get("state"))
                .toList();
    }

    // ---------------------------------------------------------------- reading the listing payload

    @SuppressWarnings("unchecked")
    private static List<Object> files(Map<String, Object> listing) {
        return listing == null ? List.of() : (List<Object>) listing.get("files");
    }

    private static List<String> names(Map<String, Object> listing) {
        return files(listing).stream().map(f -> (String) ((Map<?, ?>) f).get("name")).toList();
    }

}
