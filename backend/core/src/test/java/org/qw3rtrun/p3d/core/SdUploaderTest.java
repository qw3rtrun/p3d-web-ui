package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.msg.SdUploadProgressed;
import org.qw3rtrun.p3d.api.msg.SdUploadState;
import org.qw3rtrun.p3d.g.marlin.event.SdDonePrinting;
import org.qw3rtrun.p3d.g.marlin.event.SdPrinting;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    // -------------------------------------- the short name is persisted - issue #148's follow-on

    /**
     * The whole point of persisting rather than recomputing: a name already written to a card must
     * not move under it. A second upload of the same file is answered from the row {@code rememberShortName}
     * wrote on the first, not from a fresh call to {@link SdUploader#cardNameFor(String, boolean)} -
     * so even a name that no longer matches what the generator would choose today (standing in for
     * a future change to it) is the one that goes out.
     */
    @Test
    void aPersistedShortNameIsUsedEvenIfTheGeneratorWouldNowChooseDifferently() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE); // LFN_WRITE absent on this profile -> false
        var file = OneFile.of("a_very_long_benchy_name.gco", "G28\n");
        var files = new ShortNameCachingFiles(file);

        // stands in for a name an earlier version of the generator chose and wrote back - the
        // round trip under test is "read this before computing", not "compute, then happen to
        // match it".
        files.rememberShortName(file.id(), "OLDNAME.GCO");

        StepVerifier.create(uploaderFor(id, reactor, files, DEFAULT_MAX)
                        .start(reactor, id, file.id(), "a_very_long_benchy_name.gco", false, false))
                .assertNext(outcome -> assertInstanceOf(ControlOutcome.Accepted.class, outcome))
                .verifyComplete();

        assertEquals("M28 OLDNAME.GCO", reactor.written.getFirst(),
                "a once-persisted short name must survive a change to the generator, not be recomputed");
        assertEquals(Optional.of("OLDNAME.GCO"), files.shortName(file.id()),
                "the cache is unchanged by a read");
    }

    /**
     * The other half: a file that has never needed a short name gets one computed and written back
     * on its first upload to a machine without {@code Cap:LFN_WRITE}, and a second upload of the
     * same file reuses exactly that value rather than computing again - asserted by reading the
     * cache back explicitly, not merely by observing the same bytes on the wire twice.
     */
    @Test
    void aShortNameIsComputedOnceAndReadBackOnASecondUpload() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE);
        var file = OneFile.of("a_very_long_benchy_name.gco", "G28\n");
        var files = new ShortNameCachingFiles(file);

        assertEquals(Optional.empty(), files.shortName(file.id()), "nothing cached before the first upload");

        uploaderFor(id, reactor, files, DEFAULT_MAX)
                .start(reactor, id, file.id(), "a_very_long_benchy_name.gco", false, false)
                .block(TIMEOUT);

        var firstLine = reactor.written.getFirst();
        assertNotEquals("M28 a_very_long_benchy_name.gco", firstLine);
        var persisted = files.shortName(file.id());
        assertTrue(persisted.isPresent(), "the generated short name must be written back to the file's row");
        assertEquals("M28 " + persisted.get(), firstLine);

        reactor.written.clear();
        uploaderFor(id, reactor, files, DEFAULT_MAX)
                .start(reactor, id, file.id(), "a_very_long_benchy_name.gco", false, false)
                .block(TIMEOUT);

        assertEquals(firstLine, reactor.written.getFirst(),
                "the second upload must reuse the persisted name rather than recompute one");
        assertEquals(persisted, files.shortName(file.id()), "the cache is still exactly what the first upload wrote");
    }

    /**
     * A name that already fits DOS 8.3 needs no cache entry at all - most files never need
     * shortening, so most rows should stay with no {@code short_name} rather than one computed for
     * nothing.
     */
    @Test
    void aNameThatAlreadyFitsIsNeverCached() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE);
        var file = OneFile.of("benchy.gco", "G28\n");
        var files = new ShortNameCachingFiles(file);

        uploaderFor(id, reactor, files, DEFAULT_MAX)
                .start(reactor, id, file.id(), "benchy.gco", false, false)
                .block(TIMEOUT);

        assertEquals("M28 benchy.gco", reactor.written.getFirst());
        assertEquals(Optional.empty(), files.shortName(file.id()),
                "a name that already fits DOS 8.3 needs no persisted fallback");
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
            this(new PrinterState(id, new MachineProfile(Map.of(), capabilities, LocalDateTime.now())));
        }

        /** Over an aggregate the test has already driven into the state it needs. */
        StubReactor(PrinterState printer) {
            super(null, printer);
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

    /**
     * Wraps a {@link GcodeFileService} and adds the in-memory short-name cache
     * {@code GcodeFileServiceImpl} keeps on the row - what the override is for: {@link OneFile} is
     * a record built for the earlier tests, so this is a second, mutable fixture rather than a
     * third field bolted onto that one.
     */
    private static final class ShortNameCachingFiles implements GcodeFileService {

        private final GcodeFileService delegate;
        private final Map<UUID, String> shortNames = new java.util.concurrent.ConcurrentHashMap<>();

        ShortNameCachingFiles(GcodeFileService delegate) {
            this.delegate = delegate;
        }

        @Override
        public Publisher<GcodeFile> store(String name, Publisher<ByteBuffer> content) {
            return delegate.store(name, content);
        }

        @Override
        public Optional<GcodeFile> fetch(UUID id) {
            return delegate.fetch(id);
        }

        @Override
        public Stream<GcodeFile> fetchAll() {
            return delegate.fetchAll();
        }

        @Override
        public Publisher<ByteBuffer> read(UUID id) {
            return delegate.read(id);
        }

        @Override
        public void delete(UUID id) {
            delegate.delete(id);
        }

        @Override
        public Optional<String> shortName(UUID id) {
            return Optional.ofNullable(shortNames.get(id));
        }

        @Override
        public void rememberShortName(UUID id, String shortName) {
            shortNames.put(id, shortName);
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

        StepVerifier.create(uploader.start(reactor, id, UUID.randomUUID(), "MBP.GCO", true, false))
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
                        .start(reactor, id, file.id(), "benchy.gco", false, false))
                .assertNext(outcome -> assertInstanceOf(ControlOutcome.Accepted.class, outcome))
                .verifyComplete();
    }

    /**
     * A card print in progress refuses an upload in either mode, before anything is claimed. An
     * ASCII {@code M28} would open a second file on a card the firmware is reading from, and a
     * binary one would switch a printing machine's serial port into packet mode mid-print.
     * {@code SD printing byte} is what {@code M27} answers while a print is running or paused, so
     * the poll keeps this fresh whoever started the print.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMachinePrintingFromItsCardIsRefusedAnUpload(boolean binary) {
        var id = UUID.randomUUID();
        var printer = new PrinterState(id, new MachineProfile(Map.of(), CAPABLE, LocalDateTime.now()));
        printer.on(new SdPrinting(1234, 56789));
        var reactor = new StubReactor(printer);

        StepVerifier.create(uploaderFor(id, reactor, storeThatMustNotBeAsked(), DEFAULT_MAX)
                        .start(reactor, id, UUID.randomUUID(), "benchy.gco", binary, false))
                .assertNext(outcome -> {
                    var refused = refusalOf(outcome);
                    assertEquals(ControlOutcome.Refusal.WRONG_STATE, refused.refusal());
                    assertTrue(refused.reason().contains("printing"),
                            "the refusal has to say what the machine is busy with: " + refused.reason());
                })
                .verifyComplete();

        assertEquals(List.of(), reactor.written, "nothing may reach the wire");
        assertEquals(List.of(), reactor.announced, "a refusal is not a transfer and announces none");
    }

    /**
     * Firmware still answering {@code echo:busy: processing} is working on something - a homing, a
     * heat-up, a long move - and an {@code M28} queued behind it would open the file mid-operation.
     * Refused the same way as a print, and again before the store is asked anything.
     */
    @Test
    void aMachineWhoseFirmwareIsBusyIsRefusedAnUpload() {
        var id = UUID.randomUUID();
        var lifecycle = new ConnectionLifecycle();
        lifecycle.connectRequested();
        lifecycle.linkEstablished();
        lifecycle.busyReported("processing");
        var reactor = new StubReactor(new PrinterState(id,
                new MachineProfile(Map.of(), CAPABLE, LocalDateTime.now()), null, lifecycle));

        StepVerifier.create(uploaderFor(id, reactor, storeThatMustNotBeAsked(), DEFAULT_MAX)
                        .start(reactor, id, UUID.randomUUID(), "benchy.gco", true, false))
                .assertNext(outcome -> assertTrue(refusalOf(outcome).reason().contains("busy"),
                        "the refusal has to say the firmware is busy: " + outcome))
                .verifyComplete();

        assertEquals(List.of(), reactor.written, "nothing may reach the wire");
    }

    /**
     * A machine a job holds - assigned to it, feeding it, or paused mid-feed - is refused. The
     * firmware may look idle between two blocks of a host-fed print, so neither the connection state
     * nor the card says so; the queue is the only thing that knows. A job that has finished holds
     * nothing.
     */
    @ParameterizedTest
    @CsvSource({"ASSIGNED, true", "RUNNING, true", "PAUSED, true", "COMPLETED, false", "CANCELLED, false"})
    void aMachineHeldByAJobIsRefusedAnUpload(JobState state, boolean refused) {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE);
        var file = OneFile.of("benchy.gco", "G28\n");
        var job = new Job(UUID.randomUUID(), UUID.randomUUID(), "part.gcode", id, state, null,
                LocalDateTime.now(), null, null, null, null);
        var manager = new PrinterReactorManager(new PrinterDirectory(), PrinterState::new, event -> {
        }) {
            @Override
            public PrinterReactor runtimeOf(UUID asked) {
                return asked.equals(id) ? reactor : null;
            }
        };
        var uploader = new SdUploader(manager, file,
                machine -> machine.equals(id) ? List.of(job) : List.of(), DEFAULT_MAX);

        StepVerifier.create(uploader.start(reactor, id, file.id(), "benchy.gco", true, false))
                .assertNext(outcome -> {
                    if (refused) {
                        assertTrue(refusalOf(outcome).reason().contains("job"),
                                "the refusal has to name the job: " + outcome);
                    } else {
                        assertInstanceOf(ControlOutcome.Accepted.class, outcome);
                    }
                })
                .verifyComplete();

        if (refused) {
            assertEquals(List.of(), reactor.announced, "a refusal is not a transfer and announces none");
        }
    }

    /**
     * Linked but {@code ONLINE}: the socket is open and the firmware has not acknowledged anything
     * yet, so nothing says it can take a command at all. Measured 2026-11-26: a simulator still
     * rebooting from the last session lost the session's {@code M110}, nothing was ever
     * acknowledged, and a binary upload accepted in that state never got its {@code M28 B1} onto
     * the wire - it reported {@code UPLOADING} at 0 bytes for as long as anyone watched.
     */
    @Test
    void aMachineThatHasAcknowledgedNothingYetIsRefusedAnUpload() {
        var id = UUID.randomUUID();
        var lifecycle = new ConnectionLifecycle();
        lifecycle.connectRequested();
        lifecycle.linkEstablished();
        var reactor = new StubReactor(new PrinterState(id,
                new MachineProfile(Map.of(), CAPABLE, LocalDateTime.now()), null, lifecycle));

        StepVerifier.create(uploaderFor(id, reactor, storeThatMustNotBeAsked(), DEFAULT_MAX)
                        .start(reactor, id, UUID.randomUUID(), "benchy.gco", true, false))
                .assertNext(outcome -> assertTrue(refusalOf(outcome).reason().contains("acknowledged"),
                        "the refusal has to say why: " + outcome))
                .verifyComplete();

        assertEquals(List.of(), reactor.written, "nothing may reach the wire");
    }

    /** Once the print is over the card takes an upload again - the refusal reads the state, it is not a latch. */
    @Test
    void aMachineWhosePrintHasEndedTakesAnUpload() {
        var id = UUID.randomUUID();
        var printer = new PrinterState(id, new MachineProfile(Map.of(), CAPABLE, LocalDateTime.now()));
        printer.on(new SdPrinting(1234, 56789));
        printer.on(SdDonePrinting.INSTANCE);
        var reactor = new StubReactor(printer);
        var file = OneFile.of("benchy.gco", "G28\n");

        StepVerifier.create(uploaderFor(id, reactor, file, DEFAULT_MAX)
                        .start(reactor, id, file.id(), "benchy.gco", false, false))
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
                        .start(reactor, id, file.id(), "benchy", false, false))
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
                        .start(reactor, id, file.id(), "huge.gco", true, false))
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

        StepVerifier.create(uploader.start(reactor, id, UUID.randomUUID(), "bad\nM112.gco", false, false))
                .assertNext(outcome -> {
                    var refused = refusalOf(outcome);
                    assertEquals(ControlOutcome.Refusal.WRONG_STATE, refused.refusal());
                    assertTrue(refused.reason().contains("bare rest-of-line string"),
                            "the refusal carries the DSL's own words: " + refused.reason());
                })
                .verifyComplete();

        // the same name over the binary protocol: the file would be created with a name no later
        // M23 could select, so one rule for the field rather than two that differ by mode.
        StepVerifier.create(uploader.start(reactor, id, UUID.randomUUID(), "bad\nM112.gco", true, false))
                .assertNext(outcome -> assertEquals(ControlOutcome.Refusal.WRONG_STATE,
                        refusalOf(outcome).refusal()))
                .verifyComplete();

        assertEquals(List.of(), reactor.written, "nothing may reach the wire");
        assertEquals(List.of(), reactor.announced, "a refusal is not a transfer and announces none");
        assertNull(reactor.binaryCardName, "no binary transfer may have been opened");

        // the machine was not left busy: a legitimate upload still goes.
        var file = OneFile.of("benchy.gco", "G28\nG1 X1\n");
        StepVerifier.create(uploaderFor(id, reactor, file, DEFAULT_MAX)
                        .start(reactor, id, file.id(), "benchy.gco", false, false))
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
                        .start(reactor, id, file.id(), "mbp.gco", true, false))
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

        uploader.start(reactor, id, file.id(), "mbp.gco", true, false).block(TIMEOUT);
        reactor.acknowledged.next(96L);
        uploader.reportProgress();
        reactor.acknowledged.next(250L);
        reactor.acknowledged.complete();

        assertTrue(reactor.announced.size() >= 3,
                "wanted the open, a tick and the close: " + reactor.announced);
        assertTrue(reactor.announced.stream().allMatch(SdUploadProgressed::binary),
                "a binary transfer announced an ASCII event: " + reactor.announced);
        assertTrue(reactor.announced.stream().noneMatch(SdUploadProgressed::trimmed),
                "a binary transfer that was not asked to trim said it had: " + reactor.announced);
        assertEquals(SdUploadState.DONE, reactor.terminal().state());

        var ascii = new StubReactor(id, CAPABLE);
        var text = OneFile.of("benchy.gco", "G28\nG1 X1\n");

        uploaderFor(id, ascii, text, DEFAULT_MAX)
                .start(ascii, id, text.id(), "benchy.gco", false, false).block(TIMEOUT);

        assertFalse(ascii.announced.isEmpty());
        assertTrue(ascii.announced.stream().noneMatch(SdUploadProgressed::binary),
                "an ASCII transfer announced a binary event: " + ascii.announced);
        // the request asked for no trimming and gets it anyway: the ASCII write loop strips
        // unconditionally, so the flag is a fact about the mode rather than the caller's choice.
        assertTrue(ascii.announced.stream().allMatch(SdUploadProgressed::trimmed),
                "an ASCII transfer always strips, so every event has to say so: " + ascii.announced);
        assertEquals(SdUploadState.DONE, ascii.terminal().state());
    }

    // ------------------------------------------------- stripping comments - issue #146

    /** A comment, a blank line, and two blocks - the shape a sliced file has, in miniature. */
    private static final String COMMENTED = "; a note\n\nG28\nG1 X1\n";

    /** What the ASCII write loop would have sent for {@link #COMMENTED}, one block per line. */
    private static final String STRIPPED = "G28\nG1 X1\n";

    /**
     * Issue #146's "Trim GCode", where the only mode it can mean anything: a binary transfer hands
     * {@code MbpTransfer} the bytes that reach the card, so what it is handed <em>is</em> the file.
     * Asserted on {@code binaryData} rather than on the event, because the event would read the same
     * either way - the whole risk here is a flag that is reported and not acted on.
     */
    @Test
    void aTrimmedBinaryTransferHandsOverTheStrippedContent() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE);
        var file = OneFile.of("trim.gco", COMMENTED);

        StepVerifier.create(uploaderFor(id, reactor, file, DEFAULT_MAX)
                        .start(reactor, id, file.id(), "trim.gco", true, true))
                .assertNext(outcome -> assertInstanceOf(ControlOutcome.Accepted.class, outcome))
                .verifyComplete();

        assertEquals("trim.gco", reactor.binaryCardName);
        assertArrayEquals(STRIPPED.getBytes(StandardCharsets.UTF_8), reactor.binaryData,
                "a trimmed binary transfer carries the blocks the ASCII path would have sent, each"
                        + " followed by one \\n - not the stored file");

        reactor.acknowledged.complete();
        assertTrue(reactor.terminal().trimmed(),
                "the browser has to be told the comments actually went");
    }

    /**
     * The pair, on a file that has something to drop. {@code binaryProgressIsAbsoluteAndNotAccumulated}
     * uses a fixture with no comments in it, so it cannot tell "byte-exact" apart from "nothing to
     * drop anyway" - and byte-exactness is the whole reason the binary path exists beside the ASCII
     * one.
     */
    @Test
    void anUntrimmedBinaryTransferHandsOverTheStoredBytes() {
        var id = UUID.randomUUID();
        var reactor = new StubReactor(id, CAPABLE);
        var file = OneFile.of("verbatim.gco", COMMENTED);

        uploaderFor(id, reactor, file, DEFAULT_MAX)
                .start(reactor, id, file.id(), "verbatim.gco", true, false)
                .block(TIMEOUT);

        assertArrayEquals(COMMENTED.getBytes(StandardCharsets.UTF_8), reactor.binaryData,
                "the default stays byte-exact, comments and blank lines included");

        reactor.acknowledged.complete();
        assertFalse(reactor.terminal().trimmed());
    }

    /**
     * {@code trim} is not a second way to ask for the ASCII path: that path strips on every transfer
     * already, so the flag changes nothing it does and the blocks on the wire are the same either
     * way. Worth pinning rather than assuming - a branch wired into {@code asciiContentOf} would
     * pass every other test in this class.
     */
    @Test
    void anAsciiTransferWritesTheSameBlocksWhateverTrimSays() {
        var id = UUID.randomUUID();
        var asked = new StubReactor(id, CAPABLE);
        var notAsked = new StubReactor(id, CAPABLE);
        var file = OneFile.of("ascii.gco", COMMENTED);

        uploaderFor(id, asked, file, DEFAULT_MAX)
                .start(asked, id, file.id(), "ascii.gco", false, true).block(TIMEOUT);
        uploaderFor(id, notAsked, file, DEFAULT_MAX)
                .start(notAsked, id, file.id(), "ascii.gco", false, false).block(TIMEOUT);

        assertEquals(List.of("M28 ascii.gco", "G28", "G1 X1", "M29"), asked.written);
        assertEquals(asked.written, notAsked.written);
        assertTrue(asked.terminal().trimmed());
        assertTrue(notAsked.terminal().trimmed(),
                "an ASCII transfer strips whatever the request said, and has to report what it did");
    }
}
