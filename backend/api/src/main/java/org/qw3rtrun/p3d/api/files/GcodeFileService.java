package org.qw3rtrun.p3d.api.files;

import org.reactivestreams.Publisher;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The farm's G-code file store.
 *
 * <p>Nothing here holds a file in memory - the signatures enforce it. A sliced part is tens to
 * hundreds of megabytes, so bytes go in and come out as a {@link Publisher}, never a
 * {@code byte[]} or a {@code String}.
 *
 * <p>{@link ByteBuffer}, not Spring's {@code DataBuffer}: {@code api} must stay free of
 * frameworks, so the web layer converts at its own edge, where it already owns the pooled
 * buffer's lifetime - one copy per chunk, bounded since chunks are tens of kilobytes.
 *
 * <p>Every method that touches the database is blocking, as the rest of the persistence side is;
 * the web layer schedules them off the event loop (see {@code BlockingBoundaryTest}).
 */
public interface GcodeFileService {

    /**
     * Stores a file, answering with what it turned out to be. The same bytes stored twice are one
     * file: the content is hashed as it streams, and a hash already held is read to its end,
     * discarded, and returns the existing record under its original name - so two jobs on
     * identical bytes refer to one file rather than forcing recovery to guess which copy printed.
     *
     * @param content the bytes, streamed. Consumed exactly once
     * @return the stored file, new or already held
     */
    Publisher<GcodeFile> store(String name, Publisher<ByteBuffer> content);

    /**
     * One file's record, or empty when the farm holds no such file.
     *
     * <p>Blocking rather than a {@link Publisher}, unlike {@link #store} and {@link #read}: this
     * reads a row rather than streaming content, matching {@link #fetchAll}.
     */
    Optional<GcodeFile> fetch(UUID id);

    /** Every file the farm holds, newest first. The records only - no content is read. */
    Stream<GcodeFile> fetchAll();

    /**
     * The file's bytes, streamed from wherever they are kept. Empty when the id is unknown -
     * told apart from a legitimate empty file by asking {@link #fetch}.
     */
    Publisher<ByteBuffer> read(UUID id);

    /**
     * Forgets a file. The record goes; the stored bytes go only when no other record refers to
     * them - a guard against a future where {@link #store} no longer always returns the existing
     * record on identical bytes.
     */
    void delete(UUID id);
}
