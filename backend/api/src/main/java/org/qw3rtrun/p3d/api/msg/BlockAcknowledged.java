package org.qw3rtrun.p3d.api.msg;

/**
 * The firmware accepted a block this session sent - the one thing a host can measure progress by.
 *
 * <p>Not a reply: almost everything else on the bus is a {@code :gcode} reply, something the
 * machine said, while this is something only the transport knows - an {@code ok} is consumed by
 * the send window on the way in, so without this event the single most informative thing on the
 * inbound half reached no subscriber.
 *
 * <p>The honest measure of progress, where counting lines handed to the transport is not: after a
 * {@code Resend:} the transport replays blocks already counted, so a line count overstates and
 * then walks backwards. This is announced once the firmware has taken a block, under the number
 * it was first sent with, so a replay never double-counts.
 *
 * <p>Also the only proof a session can send at all: {@code ONLINE}/{@code IDLE} can both be
 * satisfied by auto-report traffic an earlier session left running, or a backlog queued while
 * nothing was listening - both observed on a live machine whose outbound half was dead, both
 * reading as healthy. An acknowledgement cannot be produced that way; it names a block this host
 * put on the wire in this session.
 *
 * @param lineNumber the {@code N} the block carried, as the send window assigned it
 * @param inFlight   how many blocks are still outstanding behind it - without this, acknowledgements
 *                   stopping is ambiguous between "nothing was sent" and "nothing is coming back"
 */
public record BlockAcknowledged(int lineNumber, int inFlight) {
}
