package org.qw3rtrun.p3d.api.files;

import org.reactivestreams.Publisher;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The farm's G-code file store.
 *
 * <p><strong>Nothing here holds a file in memory, and the signatures are what enforce it.</strong>
 * A sliced part is tens to hundreds of megabytes; a {@code byte[]} or a {@code String} anywhere on
 * this path is an out-of-memory error waiting for the first real print. Bytes go in as a
 * {@link Publisher} and come out as one, so neither side can accumulate without meaning to.
 *
 * <p><strong>{@link ByteBuffer} and not Spring's {@code DataBuffer}</strong>, which is the natural
 * type for this and cannot be used: {@code api} is the vocabulary every submodel is written in and
 * must stay free of frameworks, so a Spring type here would put spring-core on the compile path of
 * the persistence side and the protocol side alike. The web layer converts at its own edge, where
 * it already owns the pooled buffer's lifetime. The cost is one copy per chunk - chunks are tens of
 * kilobytes, so it is bounded - and the alternative was a buffer-lifetime contract across a module
 * boundary, which is the kind of rule that is obeyed until it is not.
 *
 * <p>Every method that touches the database is blocking, as the rest of the persistence side is.
 * The web layer schedules them off the event loop; see {@code BlockingBoundaryTest}.
 */
public interface GcodeFileService {

    /**
     * Stores a file, and answers with what it turned out to be.
     *
     * <p><strong>The same bytes stored twice are one file.</strong> The content is hashed as it
     * streams, and a file whose hash is already held is not written a second time - the upload is
     * read to its end, discarded, and the existing record returned. So a user who uploads
     * {@code benchy.gcode} and then the identical bytes as {@code benchy-final.gcode} gets back the
     * first record, under the first name. That is what "the content hash makes a re-upload the same
     * file" means, and it is the behaviour a job queue needs: two jobs on identical bytes must
     * refer to one file, or recovery has to guess which copy was being printed.
     *
     * @param name    what to call it if it turns out to be new
     * @param content the bytes, streamed. Consumed exactly once
     * @return the stored file, new or already held
     */
    Publisher<GcodeFile> store(String name, Publisher<ByteBuffer> content);

    /** One file's record, or an empty publisher when the farm holds no such file. */
    Publisher<GcodeFile> fetch(UUID id);

    /** Every file the farm holds, newest first. The records only - no content is read. */
    Stream<GcodeFile> fetchAll();

    /**
     * The file's bytes, streamed from wherever they are kept.
     *
     * <p>Empty when the id is unknown, which a caller tells apart from an empty file by asking
     * {@link #fetch} - a zero-byte G-code file is legal, if useless.
     */
    Publisher<ByteBuffer> read(UUID id);

    /**
     * Forgets a file.
     *
     * <p>The record goes; the stored bytes go only when no other record refers to them. Nothing
     * creates a second record on the same bytes today - {@link #store} returns the existing one -
     * so that is a guard against a future in which something does, and the cost of getting it wrong
     * is deleting the content under a job that is printing it.
     */
    void delete(UUID id);
}
