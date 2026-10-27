package org.qw3rtrun.p3d.api.files;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One stored G-code file: what it is called, what it is, and what the slicer said about it.
 *
 * <p><strong>Identified by its content.</strong> {@code contentHash} is what makes a re-upload of
 * the same bytes the same file rather than a second one - which is what plan item C1 wanted it for
 * and what C5's recovery will need, since a job that outlives a restart has to be able to say which
 * file it was printing and be believed.
 *
 * <p>Immutable, in the strong sense: there is no operation that changes the bytes behind a stored
 * file. Editing one means uploading another, which gets a different hash and is a different file.
 * A job can therefore hold a file id for eight hours without wondering whether it still means what
 * it meant.
 *
 * @param id          the file's identity in the farm
 * @param name        the name it was uploaded under, for a human to recognise
 * @param contentHash SHA-256 of the bytes, lower-case hex
 * @param sizeBytes   how large it is
 * @param lineCount   how many lines it has, counted while it was being stored
 * @param uploadedAt  when it was first stored. A re-upload of the same bytes does not move this
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
