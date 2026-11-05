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
 * <p>A filesystem, not a BLOB column (plan item C1): a sliced part is tens to hundreds of megabytes,
 * and through JDBC that is the database streaming it twice through a driver built for rows, with
 * every schema tool then coping with a table whose rows dwarf the rest of the database. A blob's
 * name is the SHA-256 of its bytes, fanned out two levels ({@code ab/cd/abcd...}, since a flat
 * directory of tens of thousands of entries is slow on every filesystem shipped), so writing the
 * same bytes twice produces the same path and the second write is simply skipped - dedupe falls out
 * of the naming rather than needing a check of its own.
 *
 * <p>Nothing here holds the content: the incoming publisher is written chunk by chunk to a temp file
 * while the digest and line count update in the same pass, and the result carries only the three
 * numbers. The temp file exists because the hash is not known until the last byte is seen, and the
 * final path is named after the hash.
 */
@Slf4j
public class GcodeBlobStore {

    /** Bounded and read from the file rather than kept while streaming - see {@link #headAndTail}. 64 KiB covers any slicer's preamble and enough of PrusaSlicer's trailing config block. */
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
     * Streams {@code content} to disk and answers with what it was. Runs on {@code boundedElastic}:
     * every line of it is blocking file I/O and the caller is a WebFlux event-loop thread;
     * {@code AsynchronousFileChannel} buys nothing here, since the work is a sequential append and
     * the thread is parked either way.
     */
    public Mono<Stored> write(Publisher<ByteBuffer> content) {
        return Mono.usingWhen(
                        Mono.fromCallable(this::newTempFile),
                        temp -> digestInto(temp, content).flatMap(stored -> publish(temp, stored)),
                        this::deleteQuietly)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** One pass over the bytes for three answers - re-reading afterwards to count lines would double the I/O, and the trailing-line adjustment in {@link Counting#finish()} is what a terminator-less last line needs. */
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
     * <p>{@code concatMap} with a prefetch of one is what makes "bounded" provable rather than
     * merely likely: it requests a single element, processes it, asks for the next, so exactly one
     * chunk is ever in flight and demand propagates back to the socket. {@code reduce} and
     * {@code doOnNext} request {@code Long.MAX_VALUE}; {@code toIterable} parks the overflow in a
     * 256-element queue, sixteen megabytes of heap at this chunk size. {@code GcodeBlobStoreTest}
     * asserts the in-flight count rather than trusting any of this.
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

        /** Counts, digests and writes the same bytes over three separate {@code duplicate} views - independent positions, shared memory, so no copy and no interference between the three passes. */
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

        /** Counts the last line even without a terminator, since a G-code file that ends without one still has that line; an empty file has none. */
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
     * Moves the temp file to its content-addressed home, unless something is already there - the
     * dedupe, and the common case on a farm re-uploading a part after a tweak that did not change
     * the G-code. {@code ATOMIC_MOVE} means a reader either finds the whole file or none of it; both
     * paths are on the same filesystem by construction (the temp directory is inside the root),
     * which is what makes an atomic move available at all. The temp file is left for
     * {@code usingWhen}'s cleanup to remove.
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

    /** {@code generate} rather than reading the whole file: the channel opens once, each request reads one chunk, so a slow consumer holds one chunk and not a file. */
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
     * The first and last {@link #METADATA_WINDOW} bytes of a blob, for the header parser. Both ends
     * because slicers disagree where they write their summary (Cura at the top, PrusaSlicer
     * repeating config at the bottom); bounded because tokenizing a whole two-hundred-megabyte file
     * to find four numbers is not worth it. A file smaller than two windows is returned once.
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

    /** Two levels of fan-out from the hash, then the hash itself - a flat directory slows down past tens of thousands of entries, which a farm printing daily reaches. */
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
