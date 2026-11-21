package org.qw3rtrun.p3d.api.msg;

/**
 * How far a transfer to this machine's SD card has got.
 *
 * <p>One of the host's own facts rather than something a printer said - no firmware has a word
 * for "the file is 40% uploaded". Travels on the machine's own event stream, where the Storage
 * tab is already listening.
 *
 * <p>Two counts, not interchangeable - the lesson {@code JobProgress} records and this inherits:
 * bytes are the denominator ({@link #bytesTotal} is the stored file's exact size, so
 * {@link #fraction()} is honest), while blocks have no total, deliberately - a sliced file is up
 * to a tenth comments and blank lines dropped rather than sent, so line count is an upper bound
 * and {@link #blocksSent} is worth showing but not dividing. The one case where the denominator is
 * honest and the numerator falls short of it is a {@link #trimmed} binary transfer, whose wire bytes
 * are the stripped content while {@link #bytesTotal} stays the stored file's size - so it can report
 * {@link SdUploadState#DONE} below 100%, the same already-accepted imprecision the ASCII path has.
 *
 * <p>Arrives on a timer, not per block: a real file is tens of thousands of round trips, and an
 * event each would be tens of thousands of messages for a page asking once a second. The last one
 * is exact and emitted at the end rather than waiting for the next tick.
 *
 * @param file       the name given on the card, what {@code M23}/{@code M30} take afterwards -
 *                   not the name it has on the server
 * @param bytesTotal the whole file, from the stored record; never zero for a real transfer
 * @param blocksSent lines handed to the send window - not a position in the file, see above
 * @param reason     why it ended; null while running and null when it finished cleanly
 * @param binary     whether this went over Marlin's Binary Protocol ({@code M28 B1} and packets)
 *                   rather than the ASCII {@code M28}/write-loop/{@code M29}. A field rather than
 *                   a second record because the browser already has one {@code case} for upload
 *                   progress keyed on this type's simple name, and because it makes which mode
 *                   actually ran observable at the SSE boundary - the only place the choice can be
 *                   proved rather than inferred. The two modes differ in what ends up on the card:
 *                   a binary transfer is a byte-for-byte copy, while the ASCII one re-assembles
 *                   G-code lines and stores them stripped of their {@code N} and {@code *} fields
 * @param trimmed    whether comments and blank lines were dropped rather than written to the card -
 *                   issue #146's "Trim GCode". Like {@link #binary}, what <em>ran</em> and not what
 *                   was asked for, and for the same reason: this is the only place it can be proved
 *                   rather than inferred. Always {@code true} on an ASCII transfer, where the strip
 *                   is unconditional ({@code GcodeLines.blockOf}) and so a fact rather than a
 *                   choice; on a binary transfer it is whatever the request asked for, since nothing
 *                   gates or refuses it
 */
public record SdUploadProgressed(String file,
                                 SdUploadState state,
                                 long bytesSent,
                                 long bytesTotal,
                                 long blocksSent,
                                 String reason,
                                 boolean binary,
                                 boolean trimmed) {

    /**
     * How much of the file has gone, between 0 and 1. Zero when there is nothing to divide by;
     * clamped at 1 since the byte count is taken as chunks are read and the last chunk can carry
     * past the recorded size if the file changed underneath.
     */
    public double fraction() {
        return bytesTotal <= 0 ? 0 : Math.min(1.0, (double) bytesSent / bytesTotal);
    }

    /** Whether the transfer is over, however it ended - which is when the machine is free again. */
    public boolean finished() {
        return state != SdUploadState.UPLOADING;
    }
}
