package org.qw3rtrun.p3d.api.msg;

/**
 * How far a transfer to this machine's SD card has got.
 *
 * <p>One of the host's own facts rather than something a printer said, which is what this package
 * is for: no firmware has a word for "the file is 40% uploaded". It travels on the machine's own
 * event stream because that is where the Storage tab is already listening, and because it is about
 * one machine's card.
 *
 * <p><strong>Two counts, and they are not interchangeable.</strong> That is the lesson
 * {@code JobProgress} records and this one inherits:
 *
 * <ul>
 *   <li><strong>Bytes are the denominator.</strong> {@link #bytesTotal} is the stored file's size,
 *       known exactly before a single line goes out, and {@link #bytesSent} counts the same bytes
 *       as they are read. {@link #fraction()} is therefore honest.
 *   <li><strong>Blocks have no total</strong>, deliberately. A tenth of a sliced file is comments
 *       and blank lines, which are dropped rather than sent, so the file's line count is an upper
 *       bound and not a denominator - and a progress bar driven by it would stop short of the end
 *       every time. {@link #blocksSent} is worth showing as a number; it is not worth dividing.
 * </ul>
 *
 * <p><strong>It arrives on a timer, not per block.</strong> A real file is tens of thousands of
 * blocks and each one is a round trip; an event each would be tens of thousands of messages to
 * answer a question a page asks about once a second. The last one of a transfer is exact and is
 * emitted at the end rather than waiting for the next tick.
 *
 * @param file       the name the file was given on the card, which is what {@code M23} and
 *                   {@code M30} will take afterwards - not the name it has on the server
 * @param state      where the transfer is, and - when it is over - what that left on the card
 * @param bytesSent  how much of the source file has been read and turned into blocks
 * @param bytesTotal the whole file, from the stored record. Never zero for a real transfer
 * @param blocksSent how many lines have been handed to the send window. Not a position in the
 *                   file: see above
 * @param reason     why it ended, in the words of whatever ended it. Null while it is running and
 *                   null when it finished cleanly
 */
public record SdUploadProgressed(String file,
                                 SdUploadState state,
                                 long bytesSent,
                                 long bytesTotal,
                                 long blocksSent,
                                 String reason) {

    /**
     * How much of the file has gone, between 0 and 1.
     *
     * <p>Zero when there is nothing to divide by, which is the only honest answer for a file whose
     * size is unknown - and clamped at 1, because the byte count is taken as chunks are read and
     * the last chunk can carry past the recorded size if the file changed underneath.
     */
    public double fraction() {
        return bytesTotal <= 0 ? 0 : Math.min(1.0, (double) bytesSent / bytesTotal);
    }

    /** Whether the transfer is over, however it ended - which is when the machine is free again. */
    public boolean finished() {
        return state != SdUploadState.UPLOADING;
    }
}
