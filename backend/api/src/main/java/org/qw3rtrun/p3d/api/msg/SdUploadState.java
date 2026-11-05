package org.qw3rtrun.p3d.api.msg;

/**
 * Where a transfer to a printer's SD card has got to.
 *
 * <p>Four states, not two, because how a transfer ended decides what is on the card:
 * {@link #CANCELLED} closes the file with {@code M29}, leaving a truncated but closed file safe
 * to delete and not safe to print; {@link #FAILED} may leave the file <em>open</em> - an
 * emergency stop preempts a transfer with no {@code M29} behind it, and the next reset or
 * {@code M29} closes it. That difference is the whole reason this is an enum rather than a
 * boolean.
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
