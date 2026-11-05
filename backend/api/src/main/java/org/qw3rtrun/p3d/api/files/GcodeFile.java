package org.qw3rtrun.p3d.api.files;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One stored G-code file: what it is called, what it is, and what the slicer said about it.
 *
 * <p>Identified by its content: {@code contentHash} makes a re-upload of the same bytes the same
 * file rather than a second one, which is what C5's recovery needs - a job that outlives a restart
 * has to say which file it was printing and be believed.
 *
 * <p>Immutable in the strong sense: there is no operation that changes the bytes behind a stored
 * file. Editing means uploading another, with a different hash and a different file.
 *
 * @param contentHash SHA-256 of the bytes, lower-case hex
 * @param uploadedAt  when first stored - a re-upload of the same bytes does not move this
 * @param slice       what the slicer said, never null
 */
public record GcodeFile(UUID id,
                        String name,
                        String contentHash,
                        long sizeBytes,
                        long lineCount,
                        LocalDateTime uploadedAt,
                        SliceInfo slice) {

    public GcodeFile {
        slice = slice == null ? SliceInfo.unknown() : slice;
    }
}
