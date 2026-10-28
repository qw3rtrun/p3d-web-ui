package org.qw3rtrun.p3d.api.msg;

/**
 * A machine was removed from the farm - the other half of {@link MachineJoinedFarm}.
 *
 * <p>Carries nothing: the {@code EventMessage} it travels in already says which machine, and there
 * is nothing else to know about one that has stopped existing.
 */
public record MachineLeftFarm() implements GEvent {
}
