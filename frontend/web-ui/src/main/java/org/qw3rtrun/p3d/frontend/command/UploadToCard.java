package org.qw3rtrun.p3d.frontend.command;

import java.util.UUID;

/**
 * Write a file the server already holds onto a machine's SD card.
 *
 * <p><strong>Not a {@link PrinterCommand}</strong>, and it is the only request on this surface that
 * is not. The others answer {@code toMarlin()} with the command or two they become; a transfer is
 * tens of thousands of blocks read off a file over several minutes, so it goes to
 * {@code PrinterControl.uploadToCard} rather than through {@code send}.
 *
 * <p><strong>It carries no bytes.</strong> The browser uploads to {@code /api-files} first, which
 * streams the file into a content-addressed store and records the size a progress bar needs; this
 * names the result. Two requests rather than one, and worth it: the same file can go to several
 * machines without being sent again, and a transfer that fails can be retried without a re-upload.
 *
 * @param fileId the file in the server's store
 * @param name   what to call it on the card. An extension is <strong>not</strong> required here -
 *               the server adds one if it is missing, because {@code M20} lists only printable
 *               extensions and a file without one is invisible. The name that was actually used
 *               comes back on the event stream with the first progress report
 */
public record UploadToCard(UUID fileId, String name) {

    /**
     * See {@link SdFileName} - the name reaches the printer verbatim in an {@code M28}, so it is
     * checked before anything else happens to it.
     *
     * <p>The length budget there is sized against {@code M28} in particular, which is this request
     * and not a coincidence: it is the longest of the commands that carry a filename.
     */
    public UploadToCard {
        if (fileId == null) {
            throw new IllegalArgumentException("a file is required");
        }
        name = SdFileName.checked(name);
    }
}
