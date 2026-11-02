package org.qw3rtrun.p3d.api.control;

import java.util.UUID;

/**
 * What a caller gets back from asking a machine to do something: it was taken on, or a stated
 * refusal.
 *
 * <p>The control surface's twin of {@code JobOutcome}, and it exists for the same reason. Two
 * ordinary outcomes of clicking a button - the store does not hold that file, the machine is not
 * connected - used to leave {@code PrinterControl} as an {@code IllegalArgumentException} and an
 * {@code IllegalStateException}, and a browser was told <strong>500</strong> for both. Neither is a
 * fault: the first is a stale page, the second is a printer somebody unplugged. A refusal that
 * travels as a value is answerable; a refusal that travels as an exception makes the web layer
 * guess a status from a class name, and that guess is a table that drifts.
 *
 * <p><strong>Two refusals and not five, deliberately.</strong> {@link Refusal} is as coarse as it
 * is because its only consumer is the status mapping, which is a decision taken once for the web
 * module - {@code ABSENT} is <em>not here</em> and {@code WRONG_STATE} is <em>here, and it cannot
 * do this now</em>. A third constant would have to come with a third argument for what status it
 * deserves, and nobody has one: every refusal this surface can produce is one of those two
 * sentences. What carries the specificity the enum does not is {@link Refused#reason}, which is a
 * sentence that can be put in front of a person exactly as {@code JobOutcome.Refused.reason} is -
 * so "there is no file 8f2c… to upload" and "the farm has no machine 8f2c…" are both {@code ABSENT}
 * and still tell a user different things.
 *
 * <p><strong>It names no HTTP status, and must not.</strong> {@code api} is the vocabulary every
 * submodel is written in, including the ones that have never seen a request; what a refusal is
 * worth over HTTP is the web surface's decision, and it is made in one place there.
 */
public sealed interface ControlOutcome {

    /** The machine took the work on. What answers whether it <em>finished</em> is its event stream. */
    record Accepted(UUID machineId) implements ControlOutcome {
    }

    /**
     * The machine did not take it on, and here is why.
     *
     * @param machineId the machine asked
     * @param refusal   which of the two kinds of no this is
     * @param reason    a sentence a user could be shown
     */
    record Refused(UUID machineId, Refusal refusal, String reason) implements ControlOutcome {
    }

    /** The two kinds of no - see the note on this interface for why there are only two. */
    enum Refusal {

        /** What you named is not here: an unknown machine, a file the store does not hold. */
        ABSENT,

        /** It is here, and it cannot do this now: not connected, or already busy with this. */
        WRONG_STATE
    }

    static ControlOutcome accepted(UUID machineId) {
        return new Accepted(machineId);
    }

    static ControlOutcome absent(UUID machineId, String reason) {
        return new Refused(machineId, Refusal.ABSENT, reason);
    }

    static ControlOutcome wrongState(UUID machineId, String reason) {
        return new Refused(machineId, Refusal.WRONG_STATE, reason);
    }
}
