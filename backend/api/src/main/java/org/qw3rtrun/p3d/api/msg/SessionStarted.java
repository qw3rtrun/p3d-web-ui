package org.qw3rtrun.p3d.api.msg;

/**
 * A new link to this machine: everything the host knew about it from the previous session has been
 * forgotten.
 *
 * <p><strong>What it invalidates is exactly what {@code PrinterState.snapshot()} carries</strong> -
 * so a control added to that snapshot later is covered by this without a second change. That is why
 * the boundary is stated once, by the aggregate, rather than as one signal per control.
 *
 * <p>It does not say the link is up. {@link ConnectionStateChanged} says that, and this leads it, so
 * a page applying messages in order forgets the old session before it is told there is a new one.
 *
 * <p>Not a flag on that event: a transition fires on every move, including the
 * {@code CONNECTING -> CONNECTING} of a retry, and a session boundary is one specific one.
 *
 * <p>Carries nothing: the {@link EventMessage} it travels in already says which machine, and a
 * counter or a timestamp would be a field with no reader.
 */
public record SessionStarted() {
}
