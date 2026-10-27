package org.qw3rtrun.p3d.files.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.files.SliceInfo;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The file store, joined to the row that describes it.
 *
 * <p>Two halves that have to agree: {@link GcodeBlobStore} owns the bytes and
 * {@link GcodeFileRepository} owns what is known about them. The order of operations is what keeps
 * them agreeing - the blob is written first and the row second, so the worst a crash between them
 * can do is leave bytes nothing refers to. The other order would leave a row pointing at content
 * that is not there, which is a file that appears in a list and fails when a print starts.
 */
@Slf4j
@RequiredArgsConstructor
public class GcodeFileServiceImpl implements GcodeFileService {

    private final GcodeFileRepository repo;
    private final GcodeBlobStore blobs;

    /**
     * Streams the upload to disk, then records it - or finds that it was already held.
     *
     * <p>The content is read to its end either way. A re-upload of bytes the farm already has is
     * still a request that sent them, and leaving them unread would stall the connection rather
     * than finishing it; the dedupe saves the storage, not the transfer.
     *
     * <p>The slicer metadata is read <em>after</em> the bytes have landed, from two bounded windows
     * of the stored file, rather than being accumulated while they streamed - see
     * {@link GcodeBlobStore#headAndTail}. Keeping it while streaming would mean holding whichever
     * part of the file a slicer chose to write its summary in, and PrusaSlicer chooses the end.
     */
    @Override
    public Publisher<GcodeFile> store(String name, Publisher<ByteBuffer> content) {
        return blobs.write(content)
                .flatMap(stored -> Mono.fromCallable(() -> record(name, stored))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private GcodeFile record(String name, GcodeBlobStore.Stored stored) {
        var existing = repo.findByContentHash(stored.contentHash());
        if (existing.isPresent()) {
            log.info("{} is the same file as {}, so it was not stored twice",
                    name, existing.get().getName());
            return existing.get().describe();
        }
        var entity = new org.qw3rtrun.p3d.files.model.GcodeFileEntity(
                UUID.randomUUID(), name, stored.contentHash(), stored.sizeBytes(),
                stored.lineCount(), LocalDateTime.now(), readSlice(stored.contentHash()));
        log.info("stored {} as {} ({} bytes, {} lines)",
                name, stored.contentHash(), stored.sizeBytes(), stored.lineCount());
        return repo.save(entity).describe();
    }

    /** A file whose comments could not be read is still a file, so a failure here is not fatal. */
    private SliceInfo readSlice(String contentHash) {
        try {
            return SliceInfoReader.read(blobs.headAndTail(contentHash));
        } catch (IOException e) {
            log.warn("could not read the slicer header of {}", contentHash, e);
            return SliceInfo.unknown();
        }
    }

    @Override
    public java.util.Optional<GcodeFile> fetch(UUID id) {
        return repo.findById(id).map(e -> e.describe());
    }

    @Override
    public Stream<GcodeFile> fetchAll() {
        return repo.findAll().stream()
                .sorted(Comparator.comparing(e -> e.describe().uploadedAt(),
                        Comparator.reverseOrder()))
                .map(e -> e.describe());
    }

    /**
     * The bytes, found by the row and streamed off disk.
     *
     * <p>Two steps and not one because the caller has an id and the store has a hash. The lookup is
     * blocking and the read is not, which is why only the first is scheduled here - the store
     * schedules its own.
     */
    @Override
    public Publisher<ByteBuffer> read(UUID id) {
        return Mono.fromCallable(() -> repo.findById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(found -> found
                        .map(entity -> blobs.read(entity.getContentHash()))
                        .orElseGet(Flux::empty));
    }

    /**
     * Removes the row, and the bytes if nothing else refers to them.
     *
     * <p>The check is asked after the row is gone, which is the order that cannot delete content
     * out from under a surviving reference. Nothing creates a second row on the same hash today -
     * {@link #store} returns the existing one - so this is a guard against a future in which
     * something does, and the cost of getting it wrong is deleting a file a print is reading.
     */
    @Override
    public void delete(UUID id) {
        repo.findById(id).ifPresent(entity -> {
            repo.delete(entity);
            repo.flush();
            if (repo.existsByContentHash(entity.getContentHash())) {
                log.info("{} still refers to {}, so the content stays", entity.getName(),
                        entity.getContentHash());
                return;
            }
            blobs.remove(entity.getContentHash());
        });
    }
}
