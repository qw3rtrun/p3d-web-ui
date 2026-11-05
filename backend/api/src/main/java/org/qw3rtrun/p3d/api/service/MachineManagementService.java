package org.qw3rtrun.p3d.api.service;

import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineSpec;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public interface MachineManagementService {

    /**
     * One machine's record, or empty when the farm holds no such machine.
     *
     * <p>Empty is a real answer, not an error: a page left open on a deleted printer will ask for
     * one, which is ordinary rather than a fault. This used to return a {@link Descriptor} and end
     * in {@code orElseThrow}, so {@code GET /api-manager/{uuid}} answered 500 for an unknown id -
     * the same shape {@code GcodeFileService.fetch}/{@code JobService.fetch} already used.
     */
    Optional<Descriptor> fetch(UUID uuid);

    /**
     * Registers a machine nobody has described yet - the default, since registering a printer
     * just plugged in should not require knowing its bed size. A caller that does know passes it
     * to the overload below rather than registering then editing, which would be two transactions
     * for one act.
     */
    default Descriptor add(String name, ConnectionDetails connection) {
        return add(name, connection, MachineSpec.undeclared());
    }

    Descriptor add(String name, ConnectionDetails connection, MachineSpec spec);

    /**
     * Changes what a human declared about a machine: its name, where to dial it, and its spec.
     *
     * <p>Cannot touch the profile - what a machine reported is written from firmware reports
     * alone, so an edit cannot become a claim about what the firmware said. Name and connection
     * are editable here rather than separately because they are the same kind of fact as the
     * spec: things a person knows and the machine does not.
     *
     * @param spec what was declared; {@link org.qw3rtrun.p3d.api.model.MachineSpec#undeclared()}
     *             clears it
     */
    Descriptor update(UUID uuid, String name, ConnectionDetails connection, MachineSpec spec);

    Stream<Descriptor> fetchAll();

    void delete(UUID uuid);
}
