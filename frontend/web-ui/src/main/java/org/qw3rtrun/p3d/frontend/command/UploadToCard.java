package org.qw3rtrun.p3d.frontend.command;

import java.util.UUID;

/**
 * Write a file the server already holds onto a machine's SD card.
 *
 * <p>Not a {@link PrinterCommand} - the only request on this surface that is not, since a transfer
 * is tens of thousands of blocks over several minutes and goes to
 * {@code PrinterControl.uploadToCard} rather than through {@code send}.
 *
 * <p>It carries no bytes: the browser uploads to {@code /api-files} first, which streams the file
 * into a content-addressed store, and this names the result - so the same file can reach several
 * machines without being sent again, and a failed transfer can be retried without a re-upload.
 *
 * @param fileId the file in the server's store
 * @param name   what to call it on the card. An extension is <strong>not</strong> required - the
 *               server adds one if missing, since {@code M20} lists only printable extensions and a
 *               file without one is invisible. The name actually used comes back on the event
 *               stream with the first progress report
 */
public record UploadToCard(UUID fileId, String name) {

    // See SdFileName: the name reaches the printer verbatim in an M28 - the longest of the commands
    // that carry a filename, which is why its length budget is sized against M28 specifically.
    public UploadToCard {
        if (fileId == null) {
            throw new IllegalArgumentException("a file is required");
        }
        name = SdFileName.checked(name);
    }
}
