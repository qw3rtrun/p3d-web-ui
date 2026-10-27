package org.qw3rtrun.p3d.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qw3rtrun.p3d.files.service.GcodeBlobStore;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bytes: hashed, counted and written without ever being held.
 *
 * <p>No Spring and no database. The store is the half of plan item C1 that has to be right about
 * memory, and the memory question is answerable deterministically - by counting what is in flight -
 * rather than by measuring a heap and hoping.
 */
class GcodeBlobStoreTest {

    @TempDir
    Path root;

    private GcodeBlobStore store() {
        return new GcodeBlobStore(root);
    }

    private static ByteBuffer chunk(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    void itHashesCountsAndSizesInOnePass() {
        var content = "G28\nG1 X10\nG1 X20\n";

        StepVerifier.create(store().write(Flux.just(chunk(content))))
                .assertNext(stored -> {
                    assertEquals(sha256(content), stored.contentHash());
                    assertEquals(content.length(), stored.sizeBytes());
                    assertEquals(3, stored.lineCount());
                })
                .verifyComplete();
    }

    /** A chunk boundary is not a line boundary, and the counting must not care where they fall. */
    @Test
    void aLineSplitAcrossChunksIsStillOneLine() {
        StepVerifier.create(store().write(Flux.just(chunk("G1 X"), chunk("10\nG1 "), chunk("X20\n"))))
                .assertNext(stored -> {
                    assertEquals(2, stored.lineCount());
                    assertEquals(sha256("G1 X10\nG1 X20\n"), stored.contentHash());
                })
                .verifyComplete();
    }

    /**
     * A file that does not end in a newline still has a last line.
     *
     * <p>Counting newlines alone would report one fewer, and slicers that omit the final terminator
     * are common enough that the number would be wrong for a good share of real files - which
     * matters because plan item C4 measures progress against it.
     */
    @Test
    void aFileWithNoTrailingNewlineStillCountsItsLastLine() {
        StepVerifier.create(store().write(Flux.just(chunk("G28\nG1 X10"))))
                .assertNext(stored -> assertEquals(2, stored.lineCount()))
                .verifyComplete();
    }

    @Test
    void anEmptyFileHasNoLines() {
        StepVerifier.create(store().write(Flux.empty()))
                .assertNext(stored -> {
                    assertEquals(0, stored.sizeBytes());
                    assertEquals(0, stored.lineCount());
                })
                .verifyComplete();
    }

    /**
     * <strong>The memory proof, and it is a count rather than a measurement.</strong>
     *
     * <p>Plan item C1 says upload must be "provably bounded in memory". A test that watches the
     * heap proves nothing - a garbage collector can make any implementation look fine or look bad -
     * so what is asserted instead is the thing that would cause the unboundedness: how many chunks
     * the store has asked for and not yet finished with. One, throughout, however many are offered.
     *
     * <p>It bites. {@code reduce} and {@code doOnNext} both request {@code Long.MAX_VALUE} and
     * would show 2048 here; {@code toIterable} would show up to its queue size. Only demand of one
     * at a time keeps this at one, and only that propagates back to the socket and actually slows
     * an upload down rather than buffering it.
     */
    @Test
    void theUploadNeverAsksForMoreThanOneChunk() {
        var largestRequest = new AtomicLong();
        var totalRequested = new AtomicLong();

        Flux<ByteBuffer> content = Flux.range(0, 2048)
                .map(i -> chunk("G1 X" + i + "\n"))
                .doOnRequest(n -> {
                    largestRequest.accumulateAndGet(n, Math::max);
                    totalRequested.addAndGet(n);
                });

        StepVerifier.create(store().write(content))
                .assertNext(stored -> assertEquals(2048, stored.lineCount()))
                .verifyComplete();

        assertEquals(1, largestRequest.get(),
                "the store asked for " + largestRequest.get() + " chunks at once; it may ask for one");
        assertTrue(totalRequested.get() >= 2048,
                "and it must ask once per chunk rather than once for everything, but it asked "
                        + totalRequested.get() + " times for 2048 chunks");
    }

    /**
     * The same bytes twice are stored once - plan item C1's second done-when condition.
     *
     * <p>Asserted on the filesystem rather than on the return value, because the return value would
     * look identical either way: the hash of the same bytes is the same hash whether or not they
     * were written twice. What is checked is that the directory holds one file.
     */
    @Test
    void theSameBytesAreNotStoredTwice() throws Exception {
        var content = "G28\nG1 X10\n";
        var store = store();

        StepVerifier.create(store.write(Flux.just(chunk(content)))).expectNextCount(1).verifyComplete();
        StepVerifier.create(store.write(Flux.just(chunk(content)))).expectNextCount(1).verifyComplete();

        try (Stream<Path> files = Files.walk(root)) {
            assertEquals(1, files.filter(Files::isRegularFile).count(),
                    "two uploads of the same file must leave one blob and no temporary files");
        }
    }

    /** Different bytes are different files, which is the other half of the same claim. */
    @Test
    void differentBytesAreStoredSeparately() throws Exception {
        var store = store();

        StepVerifier.create(store.write(Flux.just(chunk("G28\n")))).expectNextCount(1).verifyComplete();
        StepVerifier.create(store.write(Flux.just(chunk("G29\n")))).expectNextCount(1).verifyComplete();

        try (Stream<Path> files = Files.walk(root)) {
            assertEquals(2, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void whatWasWrittenIsWhatIsReadBack() {
        var content = "G28\nG1 X10\nG1 X20\n";
        var store = store();
        var hash = sha256(content);

        StepVerifier.create(store.write(Flux.just(chunk(content)))).expectNextCount(1).verifyComplete();

        var read = Flux.from(store.read(hash))
                .map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString())
                .reduce("", String::concat);

        StepVerifier.create(read).expectNext(content).verifyComplete();
    }

    /** A download holds one chunk at a time too - the same claim, the other direction. */
    @Test
    void theDownloadHoldsOneChunkAtATime() {
        var content = "G1 X1\n".repeat(50_000);
        var store = store();

        StepVerifier.create(store.write(Flux.just(chunk(content)))).expectNextCount(1).verifyComplete();

        StepVerifier.create(store.read(sha256(content)), 1)
                .expectNextCount(1)
                .thenCancel()
                .verify();
    }

    @Test
    void aFailedUploadLeavesNothingBehind() throws Exception {
        var store = store();

        StepVerifier.create(store.write(Flux.concat(
                        Flux.just(chunk("G28\n")),
                        Flux.error(new IllegalStateException("the client went away")))))
                .verifyError(IllegalStateException.class);

        try (Stream<Path> files = Files.walk(root)) {
            assertEquals(0, files.filter(Files::isRegularFile).count(),
                    "a half-written upload must not be left in the store or in the temp directory");
        }
    }

    @Test
    void removingTheBytesRemovesThem() {
        var store = store();
        var hash = sha256("G28\n");

        StepVerifier.create(store.write(Flux.just(chunk("G28\n")))).expectNextCount(1).verifyComplete();
        assertTrue(store.holds(hash));

        store.remove(hash);

        assertFalse(store.holds(hash));
    }
}
