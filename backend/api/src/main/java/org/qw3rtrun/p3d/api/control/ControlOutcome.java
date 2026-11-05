package org.qw3rtrun.p3d.api.control;

import java.util.UUID;

/**
 * What a caller gets back from asking a machine to do something: it was taken on, or a stated
 * refusal.
 *
 * <p>The control surface's twin of {@code JobOutcome}: a refusal that travels as a value is
 * answerable, where the {@code IllegalArgumentException}/{@code IllegalStateException} this
 * replaced made the web layer guess a status from an exception's class - and the browser was told
 * 500 for both the ordinary "no such file" and "machine not connected".
 *
 * <p>Two refusals, not five: {@code ABSENT} ("not here") and {@code WRONG_STATE} ("here, but
 * cannot do this now") are the only two the status mapping needs. The specificity lives in
 * {@link Refused#reason}, a sentence for a person, so two things both {@code ABSENT} can still
 * read differently to a user.
 *
 * <p>Names no HTTP status - that mapping is the web surface's own decision, made in one place
 * there.
 */
public sealed interface ControlOutcome {

    /** The machine took the work on. What answers whether it <em>finished</em> is its event stream. */
    record Accepted(UUID machineId) implements ControlOutcome {
    }

    /**
     * The machine did not take it on, and here is why.
     *
     * @param reason a sentence that can be shown to a user
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
