package org.qw3rtrun.p3d.files.service;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Where the bytes actually live: a content-addressed directory of files, and nothing else.
 *
 * <p><strong>A filesystem and not a BLOB column.</strong> A sliced part is tens to hundreds of
 * megabytes. Through JDBC that is the database streaming it twice, once each way, through a driver
 * built for rows - and every tool that looks at the schema then has to cope with a table whose rows
 * are the size of the rest of the database put together. Plan item C1 named this and it is the one
 * decision here that would be expensive to reverse.
 *
 * <p><strong>Content-addressed, so storing is idempotent.</strong> A blob's name is the SHA-256 of
 * its bytes, fanned out two levels - {@code ab/cd/abcd...} - because a flat directory of tens of
 * thousands of entries is slow on every filesystem that has ever been shipped. Writing the same
 * bytes twice therefore produces the same path, and the second write is skipped rather than
 * duplicated. That is plan item C1's "the same bytes uploaded twice do not duplicate storage",
 * and it falls out of the naming rather than needing a check of its own.
 *
 * <p><strong>Nothing here holds the content.</strong> The incoming publisher is written chunk by
 * chunk to a temporary file while the digest and the line count are updated in the same pass, and
 * the result carries only the three numbers. The temp file is what makes that possible: the hash
 * is not known until the last byte has been seen, and the final path is named after the hash, so
 * the bytes have to land somewhere before they can be addressed.
 */
@Slf4j
public class GcodeBlobStore {

    /**
     * How much is read from each end of a stored file when looking for slicer comments.
     *
     * <p>Bounded on purpose, and read from the file rather than kept while streaming - see
     * {@link #headAndTail}. 64 KiB is comfortably more than any slicer's preamble and enough of
     * PrusaSlicer's trailing config block to find what is worth having.
     */
    static final int METADATA_WINDOW = 64 * 1024;

    /** Big enough to make the write syscalls worthwhile, small enough to be irrelevant to the heap. */
    private static final int READ_CHUNK = 64 * 1024;

    private final Path root;

    public GcodeBlobStore(Path root) {
        this.root = root;
    }

    /** What a stored blob turned out to be. The bytes are on disk; these are all that is kept. */
    public record Stored(String contentHash, long sizeBytes, long lineCount) {
    }

    /**
     * Streams {@code content} to disk and answers with what it was.
     *
     * <p>Runs on {@code boundedElastic}: every line of it is blocking file I/O, and the caller is a
     * WebFlux request on an event loop. {@code FileChannel} has no non-blocking mode worth the name
     * for this - {@code AsynchronousFileChannel} exists and buys nothing here, since the work is a
     * sequential append and the thread would be parked either way.
     */
    public Mono<Stored> write(Publisher<ByteBuffer> content) {
        return Mono.usingWhen(
                        Mono.fromCallable(this::newTempFile),
                        temp -> digestInto(temp, content).flatMap(stored -> publish(temp, stored)),
                        this::deleteQuietly)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Writes every chunk, hashing and counting newlines as they go past.
     *
     * <p>One pass over the bytes for three answers. Counting lines by reading the file again
     * afterwards would double the I/O for a number that is free while the bytes are already in
     * hand, and parsing them to count would be slower still for no better answer - a line is a
     * {@code \n}, and a G-code file that ends without one still has a last line, which is what the
     * trailing adjustment is for.
     */
    private Mono<Stored> digestInto(Path temp, Publisher<ByteBuffer> content) {
        return Mono.using(
                () -> new Counting(temp),
                counting -> Flux.from(content)
                        .concatMap(chunk -> Mono.fromCallable(() -> counting.accept(chunk)), 1)
                        .then(Mono.fromCallable(counting::finish)),
                Counting::close);
    }

    /**
     * One chunk at a time, into the file and through the digest and the counter.
     *
     * <p><strong>{@code concatMap} with a prefetch of one is what makes "bounded" provable</strong>
     * rather than merely likely. It requests a single element, processes it, and asks for the next,
     * so exactly one chunk is ever in flight no matter how fast the network delivers - and the
     * demand propagates back to the socket, which is the only thing that actually slows an upload
     * down. The obvious shapes all fail that test: {@code reduce} and {@code doOnNext} request
     * {@code Long.MAX_VALUE} and let the producer run away, and {@code toIterable} parks the
     * overflow in a 256-element queue, which at this chunk size is sixteen megabytes of heap that
     * nothing asked for. {@code GcodeBlobStoreTest} asserts the in-flight count rather than
     * trusting any of this.
     */
    private final class Counting implements AutoCloseable {

        private final FileChannel channel;
        private final MessageDigest digest = sha256();
        private long size;
        private long lines;
        private boolean endsWithNewline = true;

        Counting(Path temp) throws IOException {
            this.channel = FileChannel.open(temp, StandardOpenOption.WRITE);
        }

        /**
         * Counts the bytes, digests them, writes them - in that order, over separate views.
         *
         * <p>Three {@code duplicate}s and not three rewinds: each view has its own position, so
         * counting does not consume the bytes the digest needs and neither disturbs the write. They
         * share the underlying memory, so this costs three ints rather than three copies.
         */
        Long accept(ByteBuffer chunk) throws IOException {
            var counting = chunk.duplicate();
            while (counting.hasRemaining()) {
                byte b = counting.get();
                size++;
                endsWithNewline = b == '\n';
                if (b == '\n') {
                    lines++;
                }
            }
            digest.update(chunk.duplicate());
            var writing = chunk.duplicate();
            while (writing.hasRemaining()) {
                channel.write(writing);
            }
            return size;
        }

        /**
         * The three answers, with the last line counted even when the file does not end in one.
         *
         * <p>A G-code file whose final line has no terminator still has that line, and a slicer
         * that omits it is common enough that counting newlines alone is simply wrong. An empty
         * file has no lines, which is why the adjustment asks about the size first.
         */
        Stored finish() {
            if (size > 0 && !endsWithNewline) {
                lines++;
            }
            return new Stored(hex(digest.digest()), size, lines);
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Moves the temporary file to its content-addressed home, unless something already lives there.
     *
     * <p>The already-there case is the dedupe, and it is the common one in a farm that re-uploads a
     * part after tweaking a setting that did not change the G-code. The temp file is left for
     * {@code usingWhen}'s cleanup to remove.
     *
     * <p>{@code ATOMIC_MOVE} so that a blob is never half-visible: a reader either finds the whole
     * file or does not find it. Both paths are on the same filesystem by construction - the temp
     * directory is inside the root - which is what makes an atomic move available at all.
     */
    private Mono<Stored> publish(Path temp, Stored stored) {
        return Mono.fromCallable(() -> {
            var target = pathFor(stored.contentHash());
            if (Files.exists(target)) {
                log.info("{} is already held ({} bytes), so the upload was not stored again",
                        stored.contentHash(), stored.sizeBytes());
                return stored;
            }
            Files.createDirectories(target.getParent());
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException raced) {
                log.info("{} was stored by somebody else while this upload was in flight",
                        stored.contentHash());
            }
            return stored;
        });
    }

    /**
     * The blob's bytes, in chunks, from the file named by {@code contentHash}.
     *
     * <p>{@code generate} rather than reading the file and emitting it: the channel is opened once,
     * each request reads one chunk, and the stream ends when the file does. So a slow consumer
     * holds one chunk and not a file, which is the whole point of handing back a publisher.
     */
    public Flux<ByteBuffer> read(String contentHash) {
        return Flux.<ByteBuffer, FileChannel>generate(
                        () -> FileChannel.open(pathFor(contentHash), StandardOpenOption.READ),
                        (channel, sink) -> {
                            try {
                                var buffer = ByteBuffer.allocate(READ_CHUNK);
                                if (channel.read(buffer) < 0) {
                                    sink.complete();
                                } else {
                                    sink.next(buffer.flip());
                                }
                            } catch (IOException e) {
                                sink.error(e);
                            }
                            return channel;
                        },
                        this::closeQuietly)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * The first and last {@link #METADATA_WINDOW} bytes of a blob, for the header parser.
     *
     * <p>Both ends because slicers disagree about where they put things: Cura writes its summary at
     * the top, PrusaSlicer repeats much of its configuration at the bottom. Bounded because the
     * alternative - tokenizing the whole file looking for comments - would turn an upload into a
     * full parse of every motion command in a two-hundred-megabyte file to find four numbers.
     *
     * <p>A file smaller than two windows is returned once rather than twice, so nothing is parsed
     * double.
     */
    byte[][] headAndTail(String contentHash) throws IOException {
        var path = pathFor(contentHash);
        long size = Files.size(path);
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (size <= 2L * METADATA_WINDOW) {
                return new byte[][]{readAt(channel, 0, (int) size)};
            }
            return new byte[][]{
                    readAt(channel, 0, METADATA_WINDOW),
                    readAt(channel, size - METADATA_WINDOW, METADATA_WINDOW)};
        }
    }

    /** Whether the farm still has these bytes, which is what a delete has to ask before removing. */
    public boolean holds(String contentHash) {
        return Files.exists(pathFor(contentHash));
    }

    /** Removes the bytes. The caller owns deciding that nothing refers to them any more. */
    public void remove(String contentHash) {
        try {
            Files.deleteIfExists(pathFor(contentHash));
        } catch (IOException e) {
            log.warn("could not remove the blob for {}", contentHash, e);
        }
    }

    private static byte[] readAt(FileChannel channel, long position, int length) throws IOException {
        var buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining() && channel.read(buffer, position + buffer.position()) >= 0) {
            // read until the window is full or the file ends
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    /**
     * Two levels of fan-out from the hash, then the hash itself.
     *
     * <p>{@code ab/cd/abcd…} rather than a flat directory, because every filesystem in use slows
     * down on a directory of tens of thousands of entries and a farm that prints every day gets
     * there.
     */
    Path pathFor(String contentHash) {
        return root.resolve(contentHash.substring(0, 2))
                .resolve(contentHash.substring(2, 4))
                .resolve(contentHash);
    }

    private Path newTempFile() throws IOException {
        var temp = root.resolve("incoming");
        Files.createDirectories(temp);
        return Files.createTempFile(temp, "upload-", ".gcode");
    }

    private Mono<Void> deleteQuietly(Path temp) {
        return Mono.fromRunnable(() -> {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                log.warn("could not remove the temporary upload {}", temp, e);
            }
        });
    }

    private void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            log.warn("could not close a blob channel", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
