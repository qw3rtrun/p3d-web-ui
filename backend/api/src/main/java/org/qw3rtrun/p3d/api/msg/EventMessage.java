package org.qw3rtrun.p3d.api.msg;

import lombok.NonNull;

import java.util.UUID;

/**
 * One thing that happened, and which machine it happened to.
 *
 * <p>{@code event} is {@code Object} because two kinds of thing travel here: most are
 * {@code :gcode} replies ({@code TemperatureRs}, {@code PositionRs}, {@code ErrorRs}, ...)
 * propagated by {@code :backend:terminal} exactly as decoded, since a Marlin farm has no use for
 * a second vocabulary for what a Marlin said; the rest are the farm's own facts no firmware can
 * state - {@link ConnectionStateChanged}, {@link SessionStarted}, {@link BlockAcknowledged},
 * {@link BlocksAbandoned}, {@link SdUploadProgressed}, {@link MachineJoinedFarm},
 * {@link MachineLeftFarm}.
 *
 * <p>No interface spans those two sets - that would mean {@code :gcode} implementing a type from
 * this module, which is not for sale. {@link #type} is the discriminator a browser switches on
 * instead.
 *
 * @param type the event's simple class name - a contract with the TypeScript client, pinned by
 *             {@code EventStreamContractTest}
 */
public record EventMessage(@NonNull UUID id, @NonNull String type, @NonNull Object event) {

    public EventMessage(@NonNull UUID id, @NonNull Object event) {
        this(id, event.getClass().getSimpleName(), event);
    }
}
