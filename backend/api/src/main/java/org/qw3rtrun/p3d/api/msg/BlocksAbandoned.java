package org.qw3rtrun.p3d.api.msg;

/**
 * The session's numbering was started over, and the blocks the host was still holding went with it.
 *
 * <p>Only the transport can know this: the firmware answers a block with {@code Resend:} for a
 * line this host never framed, visible nowhere outside the send window. Measured against MarlinSim
 * (Marlin bugfix-2.1.x) on 2026-11-01, with {@code last_N} pushed to 14:
 * <pre>
 *   -&gt; N1 G21*27
 *      Error:Line Number is not Last Line Number+1, Last Line: 14
 *      Resend: 15
 *      ok
 * </pre>
 * {@code Resend:} names {@code last_N + 1}, a number this host never sent, so the host answers with
 * {@code M110 N0} and renumbers from one; {@code lineNumber} is that {@code Resend:} value.
 *
 * <p>{@code blocks} is how many framed, unacknowledged blocks the window dropped - a
 * <strong>lower bound</strong> on what was lost, since a block released early by a stray
 * {@code ok} has already left the window uncounted. An honest upper bound does not exist at this
 * layer. A job receiving this cannot resume from its checkpoint: a checkpoint records what was
 * sent, and what was lost here is sent-minus-accepted, which the checkpoint does not carry. See
 * {@code ConnectivityIntegrationTest.aStrayAcknowledgementResynchronisesAnUnnumberedSession}.
 */
public record BlocksAbandoned(int lineNumber, int blocks) {
}
