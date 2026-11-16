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
 * @param binary whether to ask for Marlin's Binary Protocol ({@code M28 B1} and packets) rather
 *               than the ASCII {@code M28}/write-loop/{@code M29}. Omitted binds {@code false},
 *               which is the right server default: the capable path is opt-in over the wire even
 *               though the page's checkbox is ticked by default, so a client written against the
 *               older contract keeps the behaviour it was written for. A machine that never
 *               reported {@code Cap:BINARY_FILE_TRANSFER} is refused with 409 rather than quietly
 *               downgraded. <strong>{@code Boolean} and not {@code boolean}</strong>, normalised
 *               below - see the class note
 */
public record UploadToCard(UUID fileId, String name, Boolean binary) {

    // See SdFileName: the name reaches the printer verbatim in an M28 - the longest of the commands
    // that carry a filename, which is why its length budget is sized against M28 specifically.
    //
    // binary is boxed because a primitive one cannot be omitted from the body: Jackson 3's
    // FAIL_ON_NULL_FOR_PRIMITIVES is on by default, so `boolean` answers every body without the
    // field with "Cannot map `null` into type `boolean`" - a 400 on the request every existing
    // client sends, measured rather than reasoned about. Normalising here rather than at the two
    // call sites keeps binary() non-null, so unboxing it is safe; the same reason HomeAxes and
    // MoveAxes carry Boolean.
    public UploadToCard {
        if (fileId == null) {
            throw new IllegalArgumentException("a file is required");
        }
        name = SdFileName.checked(name);
        binary = binary != null && binary;
    }
}
