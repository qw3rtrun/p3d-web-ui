package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

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
 * The card in the machine - issue #43's {@code SdCard} feature, and the one with the worst
 * history: the Storage tab shipped dead twice, once when its five buttons fell off a
 * {@code default} arm in {@code PrinterState.onCommand} and once more, after that was fixed, when
 * its replies reached the host and never the browser. Every layer looked right both times, every
 * test passed, and both took a live run to find - so each test here asserts <em>both</em> ends of
 * one round trip, the command and the event, because either alone would have passed throughout.
 *
 * <p>The configuration axis is whether there is a card: Marlin has no command that asks, so the
 * farm infers it from {@code echo:SD card ok} answering an {@code M21}. The simulator always has a
 * card, so the no-card replies could not be measured, and the no-card tests assert only the
 * <em>absence</em> of {@code SdCardOk} and {@code SdFileList} - what the page keys on - never the
 * text of whatever else the machine says.
 *
 * <h2>Writing a file to the card - issue #53</h2>
 *
 * <p>The only feature here that is not a round trip: an upload is a whole file streamed through
 * the send window, and the one thing that <strong>takes the wire away from everything else</strong>
 * - between {@code M28} and {@code M29} the firmware stores what it is sent instead of running it,
 * acknowledging each line as if it had run, with nothing on either end reporting the difference. So
 * these tests have a third observable at the same boundary, {@link MockPrinter#written(String)} -
 * the card's file - and the claim is that it differs from {@link MockPrinter#commands()}: the wire
 * carried the transfer plus one interactive command, and only the transfer is in the file.
 *
 * <h2>When a transfer is refused - issue #65</h2>
 *
 * <p>All four of these answers used to be <strong>500</strong> - a file the store lacks, a machine
 * the farm lacks, a machine nothing has dialled, a second transfer onto a card already being
 * written - none a server fault, and 500 is the one answer {@code client.ts} has no path for. Each
 * test asserts the status, the refusal the body states, and that nothing happened to the machine -
 * the third matters most for the disconnected case, which used to answer 200 and then announce a
 * transfer that never started, drawing a progress bar that flipped to failed a moment later. The
 * body's field names are the contract: {@code refusal} is {@code ABSENT} when what was named is not
 * there and {@code WRONG_STATE} when it is there and cannot do this now, {@code reason} the
 * sentence the browser shows - asserted as strings a browser reads, not bound to
 * {@code ControlOutcome}.
 *
 * <p>Nothing here is timed: every mid-transfer assertion stops the machine answering with
 * {@link MockPrinter#holdRepliesFrom(String)} and lets the send window stop the conversation - the
 * same mechanism {@code PrintJobIntegrationTest} uses, since a mock that answers instantly finishes
 * a transfer before the test's next line runs.
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
     * The machine still answers (here an {@code EchoMessage}) and the host still publishes it, but
     * nothing claims to be a card - the page shows its empty state because {@code SdCardOk} did not
     * arrive, not because anything said "no".
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
     * {@code M20}'s answer is {@code Begin file list}, an entry per file, {@code End file list};
     * the entries are not claimable replies ({@code TEST.GCO 47} is {@code <word> <number>}, too
     * common a shape to claim), so they reach the transport as lines nothing wanted - before
     * {@code SdFileListReader} existed they were dropped and no listing ever reached a browser.
     * This is that assembly, end to end.
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
     * {@code M23} selects and {@code M24} starts, sent as one unit - an {@code M24} before its
     * {@code M23} starts somebody else's print. {@code :gcode}'s {@code M32} has carried a filename
     * since #37, so a single command is expressible too; which form {@code /sd/print} should send
     * is #109.
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
     * Asserted by listing the card again, not by trusting the delete: Marlin confirms no
     * {@code M30} in a form anything decodes, so "deleted" can only honestly mean "no longer there".
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
     * A name reaches the printer verbatim, so a {@code ;} would comment out the rest of the block
     * and a {@code *} would look like the start of a checksum - cheaper to refuse here than
     * discover as nonsense on a card.
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

    // ---------------------------------------------------------------- a page opened later - #85

    /**
     * The listing is asked for and answered on one stream; a second stream opens afterwards and the
     * machine says nothing more, so what it shows can only come from what the aggregate holds -
     * before #85 a late page drew an empty table until somebody pressed "list" again.
     */
    @Test
    void aPageOpenedLaterIsHandedTheCard() {
        var printer = machine(MockPrinter.marlin()).withFile("TEST.GCO", 47);
        var id = register("listed earlier", printer);
        var events = connectAndSettle(id, printer);
        command(id, "/sd/list").expectStatus().isOk();
        events.await("SdFileList");

        var late = watch(id);

        var listing = late.await("SdFileList");
        assertEquals(List.of("TEST.GCO"), names(listing));
        assertTrue(late.saw("SdCardOk"), "a listed card is a mounted one: " + late.types());
    }

    /** And once {@code M22} has gone out, a page opened afterwards is not shown a card that is not there. */
    @Test
    void aReleasedCardIsNotHandedToALatePage() {
        var printer = machine(MockPrinter.marlin()).withFile("TEST.GCO", 47);
        var id = register("released earlier", printer);
        var events = connectAndSettle(id, printer);
        command(id, "/sd/list").expectStatus().isOk();
        events.await("SdFileList");
        command(id, "/sd/release").expectStatus().isOk();
        await("M22 to reach the machine", () -> printer.sent("M22"));

        var late = watch(id);

        late.await("ConnectionStateChanged");
        quiet();
        assertFalse(late.saw("SdFileList"), "the card was released: " + late.types());
        assertFalse(late.saw("SdCardOk"), "the card was released: " + late.types());
    }

    /**
     * Issue #94, the Storage half. The card has had this property since the tab shipped: a page
     * watching while the link bounces keeps a listing and a mounted flag from a session that is
     * over, and {@code StorageTab.vue} handled no connection event at all, so nothing on either
     * side marked it.
     *
     * <p>The same {@code SessionStarted} the Settings tab gets - see
     * {@code EepromSettingsIntegrationTest.aSubscriberWatchingAcrossAReconnectIsToldThePreviousSessionEnded}
     * for why it is one event from the aggregate and not a signal per control. This asserts it
     * lands after the listing it invalidates, and that the aggregate has genuinely dropped the
     * card: a page opened after the boundary is shown neither the listing nor the mount.
     */
    @Test
    void aSubscriberWatchingAcrossAReconnectIsToldTheCardBelongedToTheOldSession() {
        var printer = machine(MockPrinter.marlin()).withFile("TEST.GCO", 47);
        var id = register("card across a bounce", printer);
        var watching = connectAndSettle(id, printer);
        command(id, "/sd/list").expectStatus().isOk();
        watching.await("SdFileList");
        var listedAt = watching.types().lastIndexOf("SdFileList");

        dropTheLinkAndWait(id, printer);

        watching.await("SessionStarted");
        assertTrue(watching.types().lastIndexOf("SessionStarted") > listedAt,
                "the boundary has to arrive after the listing it invalidates: " + watching.types());

        try (var late = watch(id)) {
            quiet();
            assertFalse(late.saw("SdFileList"),
                    "the previous session's listing belonged to it: " + late.types());
            assertFalse(late.saw("SdCardOk"),
                    "and so did the knowledge that a card was in: " + late.types());
        }
    }

    // ---------------------------------------------------------------- writing a file to the card

    /**
     * The whole feature is that two lists differ: what reached the machine is {@code M28}, the
     * file, {@code M29} - asserted whole, per §3.3, since a missing {@code M29} is a file left open
     * rather than a visible failure - while what is on the card is the file alone, because
     * everything between the two was stored rather than run.
     */
    @Test
    void writingAFileToTheCardPutsEveryBlockOfItInTheFile() {
        theWholeFileReachesTheCard(MockPrinter.marlin(), "sd-upload");
    }

    /**
     * The acknowledgement dialect is the configuration axis a transfer has, for the reason a print
     * job has it: every line is a round trip through the window, and a bare-{@code ok} machine
     * holds it at one block where {@code ok P15 B3} opens it to three - "the file arrives either
     * way" is a real claim. {@link MockPrinter#withoutCard()} is deliberately not driven, §4.2
     * obeyed rather than a gap: the simulator always has a card, so what a cardless machine answers
     * an {@code M28} has never been measured, and inventing it would be a test of the invention.
     * {@code dualExtruder()} changes nothing - a transfer consults no extruder count.
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

        // The reply half: both are decoded types nothing in this suite had exercised end to end
        // before - see the measurement in MockPrinter's M28 arm.
        assertEquals("UPLOAD.GCO", events.await("SdWritingToFile").get("file"),
                "the machine's own confirmation of which file it opened never reached the browser");
        events.await("SdDoneSaving");
    }

    /**
     * Every line of a transfer is a round trip inside the send window, so a real file takes minutes
     * and a request held open for one would time out. Proved by stopping the machine answering
     * rather than timing anything: once held, the transfer <em>cannot</em> finish, so a 200 while
     * the file is still open is a claim no loaded runner can turn into a race.
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
     * {@code M28} turns the wire into a file sink, so a command from any other source landing
     * between it and {@code M29} is written into the G-code rather than executed, and acknowledged
     * exactly as if it had run - nothing on either end reports it, and the fault surfaces hours
     * later as nonsense mid-print. {@code SdUploadProbe} measured this against MarlinSim as 675
     * bytes against 687, with exclusivity and without; this is the same claim at the level of the
     * whole assembly.
     *
     * <p>The interactive command is posted while the machine is not answering, so it is in the
     * priority queue before anything can be released - a 200 from {@code /report-temp} means the
     * {@code M105} is already applied on the aggregate's executor (§4.4). What it does next is the
     * assertion: it must wait for {@code M29} and must not appear in the file.
     *
     * <p>It is the priority being proved here, not the exclusivity - established by mutation rather
     * than reading: replacing {@code addExclusivePublisher} with {@code addPublisher} in
     * {@code PrinterReactor.uploadToCard} leaves this test green, and demoting the transfer from
     * {@code REALTIME} to {@code BACKGROUND} fails it. Priority decides who gets the <em>next</em>
     * unit of demand; exclusivity covers what priority cannot - a source handed demand before the
     * transfer was queued and still holding it. That case cannot be produced from here, since every
     * source this farm has is request-driven and spends what it is given at once; producing it
     * needs a deliberately slow publisher, which is {@code SdUploadProbe}'s {@code SlowSource} (675
     * bytes against 687) and what {@code PublisherQueueExclusivityTest} builds in
     * {@code :backend:terminal}. Exclusivity is therefore belt over braces today, and this is the
     * braces.
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
     * Tearing the transfer out would end the exclusive hold with the file still open, so the next
     * command given to the machine would be written into it - the corruption the feature exists to
     * prevent, caused by the button meant to stop it. The claim with teeth: the card holds a short
     * but <em>closed</em> file, and the very next interactive command is <em>run</em>, not stored.
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

        // The teeth: the file is closed, so the next command is executed; torn out instead, it
        // would have been stored, acknowledged, and invisible from here.
        command(id, "/report-temp").expectStatus().isOk();
        await(() -> "M105 to reach the machine. It received " + printer.commands(),
                () -> printer.sent("M105"));
        quiet();

        assertEquals(onCard, printer.written("STOPPED.GCO"),
                "the next command after a cancel was written into the file, so the file was left"
                        + " open: " + printer.written("STOPPED.GCO"));
    }

    /**
     * Measured rather than read: a probe wrote a file called {@code test}, got an empty listing
     * back, and the first conclusion was that the simulator was broken. It was not - Marlin lists
     * only {@code .gcode}, {@code .gco} and {@code .g}; anything else is on the card, prints by
     * exact name, and is invisible to every listing and therefore undeletable from a page. Asserted
     * by listing the card, the only honest way to say "it can be found".
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
     * The same validator as {@code /sd/delete}, with a worse consequence if it let one through: a
     * {@code ;} in a deleted file's name costs a rejected block, but in an {@code M28} it opens a
     * file named half of what was typed, and the whole transfer goes into it.
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

    // ---------------------------------------------------------------- refusals - issue #65

    /**
     * Used to be a 500: {@code SdUploader.start} threw {@code IllegalArgumentException}, uncaught
     * in {@code :frontend:web-ui}, and the browser showed nothing useful - an ordinary
     * stale-picker outcome, not a server fault. The second half makes this more than a status
     * assertion: a refusal that still sent {@code M28} would leave a file open on the card with
     * nothing to close it, and every next command would be written into it.
     */
    @Test
    void writingAFileTheFarmDoesNotHoldIsRefusedAsNotFound() {
        var printer = machine(MockPrinter.marlin());
        var id = register("asked for a file it has not got", printer);
        var events = connectAndSettle(id, printer);

        var refusal = absent(command(id, "/sd/upload",
                "{\"fileId\":\"" + UUID.randomUUID() + "\",\"name\":\"GHOST.GCO\"}"));
        assertEquals(id.toString(), refusal.get("machineId"), "the refusal names the machine asked");

        quiet();
        assertFalse(printer.sent("M28"),
                "a file that does not exist opened one on the card: " + printer.commands());
        assertNull(printer.writingTo());
        assertFalse(events.saw("SdUploadProgressed"),
                "a transfer that was refused must not be announced as one: " + events.types());
    }

    /**
     * The witness is the registered printer beside it: the claim is not only that the request was
     * refused but that the file went to <em>nobody</em> - an id falling back to "whichever machine
     * is around" would be the worst version of this bug, invisible from the status alone.
     */
    @Test
    void writingToTheCardOfAMachineTheFarmDoesNotHaveIsRefusedAsNotFound() {
        var witness = machine(MockPrinter.marlin());
        var id = register("a bystander", witness);
        connectAndSettle(id, witness);
        var stored = idOf(upload(GcodeFixture.unsliced("sd-upload-nomachine").named("nomachine.gcode")));
        var nobody = UUID.randomUUID();

        var refusal = absent(command(nobody, "/sd/upload",
                "{\"fileId\":\"" + stored + "\",\"name\":\"NOWHERE.GCO\"}"));
        assertEquals(nobody.toString(), refusal.get("machineId"));

        quiet();
        assertEquals(List.of(), witness.commands(),
                "a transfer addressed to nobody reached a machine that was not asked");
    }

    /**
     * Never was a 500, which made it the worst of the four: the request answered 200, the transfer
     * registered, {@code UPLOADING} went out, and the failure arrived a moment later as
     * {@code SdUploadProgressed(FAILED)} from inside {@code PrinterReactor.uploadToCard} - a
     * progress panel for a transfer that never began, flipped to failed. 409, not 404, because the
     * machine is <em>there</em>: connecting it and asking again is the whole fix, and 404 would say
     * it does not exist.
     */
    @Test
    void writingToTheCardOfAMachineThatIsNotConnectedIsRefusedAsAConflict() {
        var printer = machine(MockPrinter.marlin());
        var id = register("never dialled", printer);
        var events = watch(id);
        var file = GcodeFixture.unsliced("sd-upload-offline").named("offline.gcode").withBodyLines(4);
        var stored = idOf(upload(file));

        wrongState(command(id, "/sd/upload",
                "{\"fileId\":\"" + stored + "\",\"name\":\"OFFLINE.GCO\"}"));

        quiet();
        assertFalse(printer.sessionOpen(), "a refused transfer dialled the machine anyway");
        assertEquals(List.of(), printer.commands());
        assertFalse(events.saw("SdUploadProgressed"),
                "a transfer that never started was announced to the browser as one: "
                        + events.types());
    }

    /**
     * One at a time is not a policy, it is what {@code M28} does to the wire: a second {@code M28}
     * between the first one and its {@code M29} is written into the first file and acknowledged as
     * if it had run. So the claim with teeth is the card's contents - the first file holds the
     * first file, the second name was never opened. Stated by stopping the machine answering, not
     * by timing: the first transfer cannot finish, so the second request is demonstrably made while
     * it runs.
     */
    @Test
    void aSecondTransferIsRefusedWhileTheFirstIsStillRunning() {
        var printer = machine(MockPrinter.marlin());
        var id = register("one card at a time", printer);
        var file = GcodeFixture.unsliced("sd-upload-first").named("first.gcode").withBodyLines(24);
        var blocks = file.blocks();
        printer.holdRepliesFrom(blocks.get(5));
        var events = connectAndSettle(id, printer);

        writeToCard(id, file, "FIRST.GCO");
        stalled(printer, blocks.get(5));

        var second = idOf(upload(GcodeFixture.unsliced("sd-upload-second")
                .named("second.gcode").withBodyLines(2)));
        var refusal = wrongState(command(id, "/sd/upload",
                "{\"fileId\":\"" + second + "\",\"name\":\"SECOND.GCO\"}"));
        assertEquals(id.toString(), refusal.get("machineId"));

        printer.answerFreely();
        awaitTransfer(events, "DONE");
        closed(printer, "FIRST.GCO");

        assertEquals(1, printer.commands().stream().filter(c -> c.startsWith("M28")).count(),
                "the refused request opened a second file on the card: " + printer.commands());
        assertEquals(blocks, printer.written("FIRST.GCO"),
                "the first file has to be the first file, whole");
        assertEquals(List.of(), printer.written("SECOND.GCO"),
                "the refused transfer wrote a file anyway");
    }

    /** The only refusal this endpoint has - everything else about it is deliberately an acceptance, see below. */
    @Test
    void stoppingATransferOnAMachineTheFarmDoesNotHaveIsRefusedAsNotFound() {
        var nobody = UUID.randomUUID();

        var refusal = absent(command(nobody, "/sd/upload/cancel"));

        assertEquals(nobody.toString(), refusal.get("machineId"));
    }

    /**
     * Pinned because the obvious reading of #65 is that every "you cannot do that" becomes a 409,
     * and this one must not: the page's Cancel button and the machine's state are different clocks,
     * and a transfer that finished while the click was in flight is not an operator error - 409
     * would warn somebody whose file arrived. The second assertion is the other half: accepting it
     * must put nothing on the wire, no {@code M29} for a file nobody opened.
     */
    @Test
    void stoppingATransferThatIsNotRunningIsStillAccepted() {
        var printer = machine(MockPrinter.marlin());
        var id = register("nothing to stop", printer);
        connectAndSettle(id, printer);

        command(id, "/sd/upload/cancel").expectStatus().isOk();

        quiet();
        assertEquals(List.of(), printer.commands(),
                "a cancel with nothing to cancel reached the machine: " + printer.commands());
    }

    // ---------------------------------------------------------------- reading a refusal

    /**
     * Both halves asserted every time: the status is what a client branches on, {@code refusal}
     * says <em>which</em> kind it was - a mapping regressed to answering 404 for everything would
     * pass on the status alone.
     */
    private static Map<String, Object> absent(WebTestClient.ResponseSpec response) {
        return refusal(response.expectStatus().isNotFound(), "ABSENT");
    }

    /** A 409 whose body says what state the machine is actually in. */
    private static Map<String, Object> wrongState(WebTestClient.ResponseSpec response) {
        return refusal(response.expectStatus().isEqualTo(HttpStatus.CONFLICT), "WRONG_STATE");
    }

    private static Map<String, Object> refusal(WebTestClient.ResponseSpec response, String kind) {
        var body = json(response);
        assertEquals(kind, body.get("refusal"), "the wrong kind of refusal: " + body);
        var reason = (String) body.get("reason");
        assertNotNull(reason, "a refusal with no sentence in it is one the browser cannot show");
        assertFalse(reason.isBlank(), "a refusal with no sentence in it is one the browser "
                + "cannot show: " + body);
        return body;
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
     * Not the same moment as the {@code SdUploadProgressed} that reports the end: that one fires
     * when the <em>source</em> is exhausted ({@code M29} handed to the queue), and the block still
     * has to be written, read and stored at the far end - waiting on the state alone was a race
     * that would have passed on a developer's machine every time.
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
     * Not {@code Events.payloadOf}, which answers with the most recent of a type: a transfer emits
     * several {@code SdUploadProgressed} and only the last is terminal, so "did it ever say
     * UPLOADING" and "did it end DONE" are different questions about the same type.
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
