package org.qw3rtrun.p3d.api.msg;

/**
 * The session's numbering was started again, and the blocks the host was still holding went with it.
 *
 * <p><strong>Not a reply.</strong> Like {@link BlockAcknowledged}, this is something only the
 * transport can know: the firmware says {@code Resend:} and the host answers it or cannot, and
 * which of those happened is visible nowhere outside the send window. The firmware never says "I
 * lost your blocks" - it asks for one line, and the fact that the line is one this host has never
 * framed is a conclusion only the window can draw. Until this existed the conclusion was drawn,
 * written to a WARN, and told to nobody, so a print whose remaining blocks had just been thrown
 * away still reported {@code COMPLETED}.
 *
 * <p><strong>The state it reports, measured.</strong> Against MarlinSim (Marlin bugfix-2.1.x) on
 * 2026-11-01, with the firmware's {@code last_N} pushed to 14 and a block numbered as a fresh
 * session numbers its first:
 *
 * <pre>
 *   -&gt; N1 G21*27
 *      Error:Line Number is not Last Line Number+1, Last Line: 14
 *      Resend: 15
 *      ok
 * </pre>
 *
 * <p>The {@code Resend:} names {@code last_N + 1}, which is a number this host has never put on the
 * wire. There is nothing to replay, no partial answer worth sending, and repeating the block
 * produces the same three lines for ever - so the host sends {@code M110 N0}, numbers from one
 * again, and the blocks that were in flight under the old sequence are dropped. {@code lineNumber}
 * is the number the {@code Resend:} named, kept because it is the only thing on the wire that says
 * how far apart the two ends had drifted.
 *
 * <p><strong>What {@code blocks} is, and what it is not.</strong> It is how many framed blocks the
 * send window was still holding when the numbering restarted - blocks sent and not acknowledged,
 * which the host can no longer replay. It is <em>not</em> a count of the lines the firmware never
 * ran. A block released early by a stray {@code ok} has already left the window and is not counted
 * here; {@code ConnectivityIntegrationTest.aStrayAcknowledgementResynchronisesAnUnnumberedSession}
 * is exactly that shape and reaches the resynchronisation with nothing outstanding at all. So read
 * this as a <strong>lower bound</strong> on what was lost. The honest upper bound does not exist at
 * this layer: an {@code ok} covers acceptance into the planner, and what a firmware did with a
 * block after that is not something a host is told.
 *
 * <p><strong>A job that gets one of these cannot be resumed from its checkpoint.</strong> A
 * checkpoint records what was <em>sent</em>, and what was lost here is the difference between what
 * was sent and what was accepted - which is precisely the thing the checkpoint does not record.
 * Replaying from it would re-run blocks the machine already executed and silently skip the ones it
 * did not, and nothing at this layer knows which commands are safe to repeat: a relative move sent
 * twice is a move made twice. That is why the consumer fails the job on this rather than retrying
 * it.
 *
 * @param lineNumber the line the firmware's {@code Resend:} named, which this host does not hold
 * @param blocks how many framed blocks the resynchronisation dropped - a lower bound on what was
 *               lost, never zero, because a resynchronisation that dropped nothing is not announced
 */
public record BlocksAbandoned(int lineNumber, int blocks) {
}
