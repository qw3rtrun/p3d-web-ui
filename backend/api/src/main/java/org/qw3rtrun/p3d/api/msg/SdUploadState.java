package org.qw3rtrun.p3d.api.msg;

/**
 * Where a transfer to a printer's SD card has got to.
 *
 * <p>Four states and not two, because <strong>how a transfer ended decides what is on the
 * card</strong> and a page has to say so:
 *
 * <ul>
 *   <li>{@link #DONE} - {@code M29} closed the file and it is complete.
 *   <li>{@link #CANCELLED} - the operator stopped it. {@code M29} still went out, so the card holds
 *       a <em>truncated but closed</em> file that will appear in the next listing at whatever
 *       length it reached. It is safe to delete and not safe to print.
 *   <li>{@link #FAILED} - something else ended it, and the file may be <em>open</em>: an emergency
 *       stop preempts a transfer and there is no {@code M29} behind it. The next reset or
 *       {@code M29} closes it.
 * </ul>
 *
 * <p>The difference between the last two is the whole reason this is an enum rather than a
 * boolean: a cancel is tidy and a failure is not.
 */
public enum SdUploadState {

    /** Lines are going into the file; the machine takes nothing else until this ends. */
    UPLOADING,

    /** {@code M29} closed the file and every block was acknowledged. */
    DONE,

    /** The operator stopped it. The file was closed, so what is on the card is short but whole. */
    CANCELLED,

    /** It ended some other way - see {@code reason}, and expect the file to be open on the card. */
    FAILED
}
