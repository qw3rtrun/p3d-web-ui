package org.qw3rtrun.p3d.api.msg;

/**
 * The firmware accepted a block this session sent - the one thing a host can measure progress by.
 *
 * <p><strong>Not a reply.</strong> Almost everything else on the bus is a {@code :gcode} reply -
 * something the machine said - while this is something the transport knows and nothing else can
 * see, which is why it is one of the few events this package declares itself. An {@code ok}
 * is consumed by the send window on the way in, and no subscriber can look inside a send window, so
 * until this existed the acknowledgement - the single most informative thing on the inbound half -
 * reached nobody.
 *
 * <p><strong>It is the honest measure of progress, and the obvious one is not.</strong> A host
 * could instead count the lines it handed to the transport, and that is wrong in exactly the case
 * that matters: after a {@code Resend:} the transport replays blocks the counter has already
 * counted, so progress overstates and then has to move backwards. A block is announced here once
 * the firmware has taken it, under the number it was first sent with, so a replay does not
 * double-count and nothing ever has to be walked back.
 *
 * <p><strong>It is also the only proof that a session can send at all.</strong> A machine is
 * {@code ONLINE} when a socket is open and {@code IDLE} once the firmware has answered - but "a
 * reply arrived" is satisfied by auto-report traffic an earlier session left running, and by a
 * backlog the printer queued while nothing was listening. Both were observed on a live machine
 * whose outbound half was dead, and both read as a healthy printer. An acknowledgement cannot be
 * produced that way: it names a block this host put on the wire in this session.
 *
 * @param lineNumber the {@code N} the block carried, as the send window assigned it
 * @param inFlight how many blocks are still outstanding behind it - without this, acknowledgements
 *                 stopping is ambiguous between "nothing was sent" and "nothing is coming back",
 *                 and that distinction is what a stalled machine looks like from up here
 */
public record BlockAcknowledged(int lineNumber, int inFlight) {
}
