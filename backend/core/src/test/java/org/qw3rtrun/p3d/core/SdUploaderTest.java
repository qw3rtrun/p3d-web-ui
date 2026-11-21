package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.msg.SdUploadProgressed;
import org.qw3rtrun.p3d.api.msg.SdUploadState;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The two decisions {@link SdUploader} makes that are not plumbing: what the transfer looks like,
 * and what the file ends up being called - both asserted against the real code,
 * {@code transferOf} taking its pieces as arguments so the shape can be driven without a printer.
 */
class SdUploaderTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    // M20 lists only .gcode, .gco and .g; a file written as benchy is on the card and printable by
    // exact name, but appears in no listing - measured, not read: a probe once wrote an
    // extensionless file, got an empty listing, and the first conclusion was that the simulator was
    // broken.
    @ParameterizedTest
    @CsvSource({
            "benchy,          benchy.gco",
            "benchy.stl,      benchy.stl.gco",
            "a,               a.gco",
            "  spaced  ,      spaced.gco",
    })
    void aNameWithoutAPrintableExtensionGetsOne(String requested, String expected) {
        assertEquals(expected, SdUploader.cardNameFor(requested));
    }

    /** One that already has a printable extension is left exactly as it is, case and all. */
    @ParameterizedTest
    @CsvSource({
            "benchy.gco,      benchy.gco",
            "benchy.gcode,    benchy.gcode",
            "benchy.g,        benchy.g",
            "BENCHY.GCO,      BENCHY.GCO",
            "Benchy.GcOdE,    Benchy.GcOdE",
    })
    void aPrintableExtensionIsLeftAlone(String requested, String expected) {
        assertEquals(expected, SdUploader.cardNameFor(requested));
    }

    // the extension test is on the tail, not "contains a dot": notes.gco.txt ends in .txt and needs
    // the extension, which matching the dot anywhere would have missed.
    @Test
    void aPrintableExtensionInTheMiddleDoesNotCount() {
        assertEquals("notes.gco.txt.gco", SdUploader.cardNameFor("notes.gco.txt"));
    }

    // -------------------------------------- the short-name fallback - issue #148

    // LongFileNameProbe against MarlinSim (bugfix-2.1.x, compiled Nov 18 2026) on 2026-11-21,
    // Cap:LFN_WRITE:0: an M28 whose name is already DOS 8.3 opens, writes and lists back; anything
    // else is echoed "Now fresh file: <name>" and then "open failed, File: <name>." while the M28
    // line still answers ok, so nothing is written and the next content line runs live on the
    // machine instead. Eight base characters is the boundary that worked.
    @Test
    void anExactlyEightThreeNameIsLeftAloneOnAMachineThatCannotWriteALongOne() {
        assertEquals("eightchr.gco", SdUploader.cardNameFor("eightchr.gco", false));
    }

    /**
     * Each of these was measured as failing identically on that simulator, for a different reason:
     * {@code ninechars.gco} is one character over the eight-character base,
     * {@code my file.gco} is short enough and carries a space, and
     * {@code a_very_long_benchy_name.gco} is both long and full of underscores. Length is not the
     * whole rule - an illegal character is enough on its own.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ninechars.gco", "my file.gco", "a_very_long_benchy_name.gco"})
    void aNameTheFirmwareWouldRefuseIsShortenedToFit(String requested) {
        var shortened = SdUploader.cardNameFor(requested, false);

        assertNotEquals(requested, shortened,
                "this name would have been answered 'open failed' with nothing written");
        assertDos83(shortened);
    }

    /** The extension is checked on its own: {@code benchy} fits in eight, {@code gcode} does not fit in three. */
    @Test
    void anExtensionOverThreeCharactersIsShortenedEvenWhenTheBaseFits() {
        var shortened = SdUploader.cardNameFor("benchy.gcode", false);

        assertNotEquals("benchy.gcode", shortened);
        assertDos83(shortened);
    }

    /**
     * The same requested name always becomes the same card name, so an operator who uploads twice
     * overwrites one file rather than filling the card with near-duplicates - the fingerprint is a
     * hash of the name and nothing else, with no clock and no counter in it.
     */
    @Test
    void theShortNameIsDeterministic() {
        assertEquals(SdUploader.cardNameFor("a_very_long_benchy_name.gco", false),
                SdUploader.cardNameFor("a_very_long_benchy_name.gco", false));
    }

    /**
     * Two names sharing the four-character prefix the short name is built from must still differ,
     * which is what the fingerprint is for. Asserted as inequality rather than against literals, so
     * the test says the property instead of re-deriving the hash.
     */
    @Test
    void twoNamesSharingAPrefixGetDifferentShortNames() {
        assertNotEquals(SdUploader.cardNameFor("averylongname1.gco", false),
                SdUploader.cardNameFor("averylongname2.gco", false));
    }

    /** {@code Cap:LFN_WRITE} is the gate, and a machine that reported it gets the name verbatim. */
    @Test
    void aMachineThatReportedLfnWriteKeepsTheNameItWasGiven() {
        assertEquals("a_very_long_benchy_name.gco",
                SdUploader.cardNameFor("a_very_long_benchy_name.gco", true));
    }

    /** The shape the probe measured as the only one that opens: 8.3, uppercase and digits, one tilde. */
    private static void assertDos83(String name) {
        var dot = name.lastIndexOf('.');
        assertTrue(dot > 0, "a card name always has an extension: " + name);
        var base = name.substring(0, dot);
        var extension = name.substring(dot + 1);

        assertTrue(base.length() <= 8, "base too long for DOS 8.3: " + name);
        assertTrue(extension.length() <= 3, "extension too long for DOS 8.3: " + name);
        assertTrue(base.matches("[A-Z0-9~]+"),
                "a generated base is uppercase A-Z0-9 with the one tilde: " + name);
        assertTrue(extension.matches("[A-Z0-9]+"),
                "a generated extension is uppercase A-Z0-9: " + name);
    }

    // ------------------------------------------------- the shape of a transfer

    /** Open the file, write every block of it, close it. */
    @Test
    void aTransferIsTheOpenTheFileAndTheClose() {
        var stop = Sinks.<Void>empty();

        StepVerifier.create(SdUploader.transferOf(
                        "benchy.gco", Flux.just("G28", "G1 X1", "G1 X2"), stop.asMono()))
                .expectNext("M28 benchy.gco")
                .expectNext("G28", "G1 X1", "G1 X2")
                .expectNext("M29")
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * A stop still closes the file - the one claim here about correctness, not tidiness. Cancelling
     * outright instead would leave the file open: the exclusive hold would end, and the very next
     * command given to the machine would be written into the G-code - the corruption this mechanism
     * exists to prevent, caused by the button meant to stop it.
     */
    @Test
    void aStoppedTransferStillSendsTheClose() {
        var stop = Sinks.<Void>empty();
        var content = TestPublisher.<String>create();

        StepVerifier.create(SdUploader.transferOf("benchy.gco", content.flux(), stop.asMono()))
                .expectNext("M28 benchy.gco")
                .then(() -> content.next("G28"))
                .expectNext("G28")
                .then(() -> content.next("G1 X1"))
                .expectNext("G1 X1")

                // the operator presses Cancel - the source is not torn out, it is told to stop.
                .then(() -> stop.tryEmitEmpty())

                .expectNext("M29")
                .expectComplete()
                .verify(TIMEOUT);
    }

    // a narrow but real case: the transfer is queued, the operator changes their mind, and the file
    // is read a moment later - an M28 with no M29 behind it is the worst outcome, so the close is
    // unconditional.
    @Test
    void aTransferStoppedBeforeItStartedStillCloses() {
        var stop = Sinks.<Void>empty();
        stop.tryEmitEmpty();

        StepVerifier.create(SdUploader.transferOf("benchy.gco", Flux.never(), stop.asMono()))
                .expectNext("M28 benchy.gco", "M29")
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * The binary transfer's shape: switch to the protocol, emit no G-code at all while the packets
     * go, then remount the card. The middle emits nothing <em>and does not complete</em> - the
     * exclusive publisher is the queue's head and has to stay stalled, which is what keeps an
     * {@code M105} from the temperature poller out of the middle of a packet - so an unexpected
     * {@code onNext} here, or an {@code M21} before the gate fires, is the bug this test exists for.
     */
    @Test
    void aBinaryTransferIsTheSwitchNothingAndTheRemount() {
        var transferred = Sinks.<Void>empty();

        StepVerifier.create(SdUploader.binaryTransferOf(transferred.asMono()))
                .expectNext("M28 B1")
                .expectNoEvent(Duration.ofMillis(50))
                .then(transferred::tryEmitEmpty)
                .expectNext("M21")
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * A cancelled or failed binary transfer still remounts. The gate fires from {@code doFinally},
     * so it fires on every way the packets can end - and the firmware released the card when the
     * transfer closed, so skipping the {@code M21} would break {@code M20} for the rest of the
     * session. A gate that has already fired before anything subscribes is the narrow case: the
     * transfer was queued and gave up before the queue reached it.
     */
    @Test
    void aBinaryTransferThatSentNothingStillRemounts() {
        var transferred = Sinks.<Void>empty();
        transferred.tryEmitEmpty();

        StepVerifier.create(SdUploader.binaryTransferOf(transferred.asMono()))
                .expectNext("M28 B1", "M21")
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * The opening command is <strong>built</strong>, not concatenated, so the name meets
     * {@code bareString} where it becomes bytes rather than where it entered the system. Concatenated
     * instead, a name carrying {@code \n} is not a rejected block: it is an {@code M28} plus a second
     * command nobody wrote, with the {@code N} word on one side of the break and the checksum on the
     * other, immediately in front of a file transfer the firmware is now mis-framing. Issue #82.
     */
    @Test
    void aNameThatWouldEndTheBlockCannotBeBuiltIntoATransfer() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> SdUploader.transferOf("a.gco\nM112", Flux.just("G28"),
                        Sinks.<Void>empty().asMono()));

        assertTrue(refused.getMessage().contains("bare rest-of-line string"),
                "the DSL's own refusal, not a second rule here: " + refused.getMessage());
    }

    /** An empty file is an open and a close, and the firmware is happy with that. */
    @Test
    void anEmptyFileIsStillAFile() {
        StepVerifier.create(SdUploader.transferOf("empty.gco", Flux.empty(), Sinks.<Void>empty().asMono()))
                .expectNext("M28 empty.gco", "M29")
                .expectComplete()
                .verify(TIMEOUT);
    }

    // ------------------------------------------------- a transfer, end to end, without a printer

    private static final long DEFAULT_MAX = SdUploader.DEFAULT_BINARY_MAX_BYTES;

    private static final Map<String, Boolean> CAPABLE =
            Map.of("SDCARD", true, "SD_WRITE", true, "BINARY_FILE_TRANSFER", true);

    private static final Map<String, Boolean> INCAPABLE =
            Map.of("SDCARD", true, "SD_WRITE", true, "BINARY_FILE_TRANSFER", false);

    /**
     * A machine that is linked and reports {@code capabilities}. {@code linked()} is overridden
     * rather than driven through the lifecycle - the link check itself is
     * {@code PrinterControlServiceTest}'s - but the profile is the real one, read back out of
     * {@code PrinterState.getProfile()}, because the capability refusal is the point of these tests.
     */
    private static final class StubReactor extends PrinterReactor {

        private final List<SdUploadProgressed> announced = new CopyOnWriteArrayList<>();
        private final List<String> written = new CopyOnWriteArrayList<>();
        private final TestPublisher<Long> acknowledged = TestPublisher.create();

        private volatile String binaryCardName;
        private volatile byte[] binaryData;

        StubReactor(UUID id, Map<String, Boolean> capabilities) {
            super(null, new PrinterState(id,
                    new MachineProfile(Map.of(), capabilities, LocalDateTime.now())));
        }

        @Override
        public boolean linked() {
            return true;
        }

        @Override
        public void announce(Object event) {
            announced.add((SdUploadProgressed) event);
        }

        @Override
        public Mono<Void> uploadToCard(Publisher<String> transfer) {
            return Flux.from(transfer).doOnNext(written::add).then();
        }

        @Override
        public Flux<Long> binaryTransfer(String cardName, byte[] data, Mono<Void> cancel) {
            binaryCardName = cardName;
            binaryData = data;
            return acknowledged.flux();
        }

        SdUploadProgressed terminal() {
            var last = announced.getLast();
            assertTrue(last.finished(), "the transfer has not ended: " + last);
            return last;
        }
    }

    /** One file and its bytes; anything else asked of the store fails the test. */
    private record OneFile(GcodeFile file, byte[] content) implements GcodeFileService {

        static OneFile of(String name, String content) {
            var bytes = content.getBytes(StandardCharsets.UTF_8);
            return new OneFile(new GcodeFile(UUID.randomUUID(), name, "hash", bytes.length,
                    0, LocalDateTime.now(), null), bytes);
        }

        /** A record claiming {@code sizeBytes} without holding that many - what the ceiling is checked against. */
        static OneFile claiming(String name, long sizeBytes) {
            return new OneFile(new GcodeFile(UUID.randomUUID(), name, "hash", sizeBytes,
                    0, LocalDateTime.now(), null), new byte[0]);
        }

        UUID id() {
            return file.id();
        }

        @Override
        public Optional<GcodeFile> fetch(UUID id) {
            return id.equals(file.id()) ? Optional.of(file) : Optional.empty();
        }

        @Override
        public Publisher<ByteBuffer> read(UUID id) {
            // two chunks, so the binary collector is actually made to join them.
            var half = content.length / 2;
            return Flux.just(ByteBuffer.wrap(content, 0, half),
                    ByteBuffer.wrap(content, half, content.length - half));
        }

        @Override
        public Publisher<GcodeFile> store(String name, Publisher<ByteBuffer> content) {
            return fail("the file store was written to");
        }

        @Override
        public Stream<GcodeFile> fetchAll() {
            return fail("the file store was listed");
        }

        @Override
        public void delete(UUID id) {
            fail("the file store was deleted from");
        }
    }

    /** A store that fails the test if it is consulted at all - pins the order of the checks. */
    private static GcodeFileService storeThatMustNotBeAsked() {
        return new GcodeFileService() {
            @Override
            public Publisher<GcodeFile> store(String name, Publisher<ByteBuffer> content) {
                return fail("the file store was written to");
            }

            @Override
            public Optional<GcodeFile> fetch(UUID id) {
                return fail("the file store was consulted for a transfer that cannot happen");
            }

            @Override
            public Stream<GcodeFile> fetchAll() {
                return fail("the file store was listed");
            }

            @Override
            public Publisher<ByteBuffer> read(UUID id) {
                return fail("the file store was read");
            }

            @Override
            public void delete(UUID id) {
                fail("the file store was deleted from");
            }
        };
    }

    /** The uploader under test, with {@code reactor} as the only machine the farm has a runtime for. */
    private static SdUploader uploaderFor(UUID id, StubReactor reactor, GcodeFileService files,
                                          long binaryMaxBytes) {
        var manager = new PrinterReactorManager(new PrinterDirectory(), PrinterState::new, event -> {
        }) {
            @Override
            public PrinterReactor runtimeOf(UUID asked) {
                return asked.equals(id) ? reactor : null;
            }
        };
        return new SdUploader(manager, files, binaryMaxBytes);
    }

    private static ControlOutcome.Refused refusalOf(ControlOutcome outcome) {
        return assertInstanceOf(ControlOutcome.Refused.class, outcome,
                "expected a refusal and got " + outcome);
    }

    /**
     * A machine that never reported {@code Cap:BINARY_FILE_TRANSFER} is refused, and - the half with
     * teeth - <strong>nothing is started</strong>: the refusal happens in front of the one-at-a-time
     * map, so the machine is not left marked busy and the operator's next upload, in whichever mode,
     * is not refused as "already writing".
     */
    @Test
    void anIncapableMachineIsRefusedABinaryTransferAndNothingIsStarted() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, INCAPABLE);
        // the store must not even be consulted: the capability is a field read and the lookup a
        // blocking round trip, so the cheap and certain check goes first.
        var uploader = uploaderFor(id, reactor, storeThatMustNotBeAsked(), DEFAULT_MAX);

        StepVerifier.create(uploader.start(reactor, id, UUID.randomUUID(), "MBP.GCO", true))
                .assertNext(outcome -> {
                    var refused = refusalOf(outcome);
                    assertEquals(ControlOutcome.Refusal.WRONG_STATE, refused.refusal());
                    assertTrue(refused.reason().contains("Cap:BINARY_FILE_TRANSFER"),
                            "the refusal has to name the capability: " + refused.reason());
                })
                .verifyComplete();

        assertEquals(List.of(), reactor.written, "nothing may reach the wire");
        assertEquals(List.of(), reactor.announced, "a refusal is not a transfer and announces none");

        // the machine was not left busy: a legitimate ASCII upload still goes.
        var file = OneFile.of("benchy.gco", "G28\nG1 X1\n");
        StepVerifier.create(uploaderFor(id, reactor, file, DEFAULT_MAX)
                        .start(reactor, id, file.id(), "benchy.gco", false))
                .assertNext(outcome -> assertInstanceOf(ControlOutcome.Accepted.class, outcome))
                .verifyComplete();
    }

    /** A capable machine asked for ASCII gets ASCII, verbatim - the existing path, unchanged by the choice existing. */
    @Test
    void aCapableMachineAskedForAsciiStillTakesTheAsciiPath() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE);
        var file = OneFile.of("benchy.gco", "G28\nG1 X1\nG1 X2\n");

        StepVerifier.create(uploaderFor(id, reactor, file, DEFAULT_MAX)
                        .start(reactor, id, file.id(), "benchy", false))
                .assertNext(outcome -> assertInstanceOf(ControlOutcome.Accepted.class, outcome))
                .verifyComplete();

        assertEquals("M28 benchy.gco", reactor.written.getFirst());
        assertEquals("M29", reactor.written.getLast());
        assertTrue(reactor.written.contains("G28"), "the file's blocks: " + reactor.written);
        assertNull(reactor.binaryCardName, "no binary transfer may have been opened");
    }

    /**
     * Over the ceiling is a refusal naming both the file's size and the limit, and again nothing is
     * started. The ceiling exists because {@code MbpTransfer} in {@code :gcode} does no I/O and so
     * needs the whole file as one array - a 400 MiB sliced part would be 400 MiB of heap.
     */
    @Test
    void aFileOverTheCeilingIsRefusedABinaryTransfer() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE);
        var file = OneFile.claiming("huge.gco", 2_000);

        StepVerifier.create(uploaderFor(id, reactor, file, 1_024)
                        .start(reactor, id, file.id(), "huge.gco", true))
                .assertNext(outcome -> {
                    var refused = refusalOf(outcome);
                    assertEquals(ControlOutcome.Refusal.WRONG_STATE, refused.refusal());
                    assertTrue(refused.reason().contains("1024"),
                            "the refusal has to name the limit: " + refused.reason());
                    assertTrue(refused.reason().contains("2000"),
                            "the refusal has to name the file's size: " + refused.reason());
                })
                .verifyComplete();

        assertEquals(List.of(), reactor.written);
        assertEquals(List.of(), reactor.announced);
    }

    /**
     * A name that cannot be written in a G-code block is refused <strong>without the HTTP
     * boundary</strong>, which is the whole of issue #82. {@code PrinterController}'s
     * {@code UploadToCard} runs a name through {@code SdFileName.checked} and answers 400, so a
     * browser never gets here - but {@code PrinterControl.uploadToCard} is a public method of the
     * {@code api} module and a farm that schedules its own uploads is the obvious next caller.
     * Asserted at {@code start}, the entry such a caller uses, and with a store that fails the test
     * if it is consulted: the name is pure CPU and the lookup a blocking round trip, so the cheap and
     * certain check goes first. The second half - that a legitimate upload still goes afterwards - is
     * the one with teeth: a refusal landing after the one-at-a-time claim would leave the machine
     * marked busy with no transfer left to clear it.
     */
    @Test
    void aNameThatWouldCorruptTheBlockIsRefusedWithoutTheHttpBoundary() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE);
        var uploader = uploaderFor(id, reactor, storeThatMustNotBeAsked(), DEFAULT_MAX);

        StepVerifier.create(uploader.start(reactor, id, UUID.randomUUID(), "bad\nM112.gco", false))
                .assertNext(outcome -> {
                    var refused = refusalOf(outcome);
                    assertEquals(ControlOutcome.Refusal.WRONG_STATE, refused.refusal());
                    assertTrue(refused.reason().contains("bare rest-of-line string"),
                            "the refusal carries the DSL's own words: " + refused.reason());
                })
                .verifyComplete();

        // the same name over the binary protocol: the file would be created with a name no later
        // M23 could select, so one rule for the field rather than two that differ by mode.
        StepVerifier.create(uploader.start(reactor, id, UUID.randomUUID(), "bad\nM112.gco", true))
                .assertNext(outcome -> assertEquals(ControlOutcome.Refusal.WRONG_STATE,
                        refusalOf(outcome).refusal()))
                .verifyComplete();

        assertEquals(List.of(), reactor.written, "nothing may reach the wire");
        assertEquals(List.of(), reactor.announced, "a refusal is not a transfer and announces none");
        assertNull(reactor.binaryCardName, "no binary transfer may have been opened");

        // the machine was not left busy: a legitimate upload still goes.
        var file = OneFile.of("benchy.gco", "G28\nG1 X1\n");
        StepVerifier.create(uploaderFor(id, reactor, file, DEFAULT_MAX)
                        .start(reactor, id, file.id(), "benchy.gco", false))
                .assertNext(outcome -> assertInstanceOf(ControlOutcome.Accepted.class, outcome))
                .verifyComplete();
    }

    /**
     * Progress on a binary transfer is <strong>absolute</strong>. The transport reports cumulative
     * acknowledged payload bytes, so 96, 192 then 250 is a 250-byte file that has finished, not a
     * 538-byte one - and {@code addAndGet}, which is what the ASCII path correctly does with its own
     * chunk sizes, would run a progress bar past 100% and make a finished transfer read as
     * over-sent. This is the test that catches it.
     */
    @Test
    void binaryProgressIsAbsoluteAndNotAccumulated() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE);
        var file = OneFile.claiming("mbp.gco", 250);

        StepVerifier.create(uploaderFor(id, reactor, file, DEFAULT_MAX)
                        .start(reactor, id, file.id(), "mbp.gco", true))
                .assertNext(outcome -> assertInstanceOf(ControlOutcome.Accepted.class, outcome))
                .verifyComplete();

        assertEquals("mbp.gco", reactor.binaryCardName);
        assertNotNull(reactor.binaryData, "the whole file has to have been handed over");

        reactor.acknowledged.next(96L, 192L, 250L);
        reactor.acknowledged.complete();

        var done = reactor.terminal();
        assertEquals(SdUploadState.DONE, done.state());
        assertEquals(250, done.bytesSent(), "cumulative, not accumulated - 538 is the bug");
        assertEquals(3, done.blocksSent(), "one per acknowledged packet");
        assertEquals(1.0, done.fraction());
    }

    /**
     * Every event of a binary transfer says so, and every event of an ASCII one says it did not -
     * the initial one, the 1 Hz pump's, and the terminal one, since all three come out of the one
     * {@code Transfer.at}. It is the only place the mode that actually ran is observable, so a flag
     * true on the first event and false on the last would be worse than no flag at all.
     */
    @Test
    void everyEventOfATransferSaysWhichModeRan() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE);
        var file = OneFile.claiming("mbp.gco", 250);
        var uploader = uploaderFor(id, reactor, file, DEFAULT_MAX);

        uploader.start(reactor, id, file.id(), "mbp.gco", true).block(TIMEOUT);
        reactor.acknowledged.next(96L);
        uploader.reportProgress();
        reactor.acknowledged.next(250L);
        reactor.acknowledged.complete();

        assertTrue(reactor.announced.size() >= 3,
                "wanted the open, a tick and the close: " + reactor.announced);
        assertTrue(reactor.announced.stream().allMatch(SdUploadProgressed::binary),
                "a binary transfer announced an ASCII event: " + reactor.announced);
        assertEquals(SdUploadState.DONE, reactor.terminal().state());

        var ascii = new StubReactor(id, CAPABLE);
        var text = OneFile.of("benchy.gco", "G28\nG1 X1\n");

        uploaderFor(id, ascii, text, DEFAULT_MAX)
                .start(ascii, id, text.id(), "benchy.gco", false).block(TIMEOUT);

        assertFalse(ascii.announced.isEmpty());
        assertTrue(ascii.announced.stream().noneMatch(SdUploadProgressed::binary),
                "an ASCII transfer announced a binary event: " + ascii.announced);
        assertEquals(SdUploadState.DONE, ascii.terminal().state());
    }
}
