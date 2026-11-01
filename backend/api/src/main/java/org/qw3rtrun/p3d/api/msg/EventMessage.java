package org.qw3rtrun.p3d.api.msg;

import lombok.NonNull;

import java.util.UUID;

/**
 * One thing that happened, and which machine it happened to.
 *
 * <p><strong>{@code event} is {@code Object} because two kinds of thing travel here.</strong> Most
 * of them are {@code :gcode} replies - a {@code TemperatureRs}, a {@code PositionRs}, an
 * {@code ErrorRs} - propagated by {@code :backend:terminal} exactly as the protocol decoded them,
 * since a Marlin farm has no use for a second vocabulary for what a Marlin said. The rest are the
 * farm's own facts, which no firmware can state: {@link ConnectionStateChanged},
 * {@link BlockAcknowledged}, {@link BlocksAbandoned}, {@link SdUploadProgressed},
 * {@link MachineJoinedFarm}, {@link MachineLeftFarm}.
 *
 * <p>No interface spans those two sets, and inventing one would mean {@code :gcode} implementing a
 * type from this module - which is the dependency that keeps the protocol portable and is not for
 * sale. {@link #type} is the discriminator instead, and it is what a browser switches on.
 *
 * @param id    the machine
 * @param type  the event's simple class name, which is a contract with the TypeScript client -
 *              {@code EventStreamContractTest} pins the strings
 * @param event a {@code :gcode} reply, or one of this package's own events
 */
public record EventMessage(@NonNull UUID id, @NonNull String type, @NonNull Object event) {

    public EventMessage(@NonNull UUID id, @NonNull Object event) {
        this(id, event.getClass().getSimpleName(), event);
    }
}
