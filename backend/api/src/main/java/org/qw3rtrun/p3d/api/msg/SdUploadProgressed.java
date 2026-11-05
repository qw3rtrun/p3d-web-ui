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
 * and {@link #blocksSent} is worth showing but not dividing.
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
 */
public record SdUploadProgressed(String file,
                                 SdUploadState state,
                                 long bytesSent,
                                 long bytesTotal,
                                 long blocksSent,
                                 String reason) {

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
