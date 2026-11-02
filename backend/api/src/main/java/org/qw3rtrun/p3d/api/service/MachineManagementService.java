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
     * <p><strong>Empty is a real answer and not an error.</strong> A page left open on a printer
     * somebody has since deleted will ask for it, and that is an ordinary thing for a farm to be
     * asked rather than a fault in it. This used to return a {@link Descriptor} and end in
     * {@code orElseThrow}, so the one way to say "no such machine" was an exception - and
     * {@code GET /api-manager/{uuid}} answered <strong>500</strong> for an unknown id, which is the
     * status that means "this server is broken".
     *
     * <p>The caller decides what to say about it, which is how {@code GcodeFileService.fetch} and
     * {@code JobService.fetch} already read - so every GET-by-id in this application now has the
     * same shape and the web layer makes the same decision in one place.
     */
    Optional<Descriptor> fetch(UUID uuid);

    /**
     * Registers a machine nobody has described yet.
     *
     * <p>The default, because registering a printer you have just plugged in should not require
     * knowing its bed size - and because the nine callers that have nothing to declare should not
     * have to say so. A caller that does know passes it to the overload below rather than
     * registering and then editing, which would be two transactions and two announcements for one
     * act.
     */
    default Descriptor add(String name, ConnectionDetails connection) {
        return add(name, connection, MachineSpec.undeclared());
    }

    Descriptor add(String name, ConnectionDetails connection, MachineSpec spec);

    /**
     * Changes what a human declared about a machine: its name, where to dial it, and its spec.
     *
     * <p><strong>It cannot touch the profile, and that is the point.</strong> What a machine
     * reported is written from firmware reports alone, so an edit cannot silently become a claim
     * about what the firmware said - see {@link org.qw3rtrun.p3d.api.model.MachineSpec}.
     *
     * <p>The name and the connection are editable here rather than in a separate call because they
     * are the same kind of fact as the spec - things a person knows and the machine does not - and
     * because a form that can set a nozzle diameter but not fix a mistyped hostname is a worse
     * product than one call is worth.
     *
     * @param spec what was declared; {@link org.qw3rtrun.p3d.api.model.MachineSpec#undeclared()}
     *             clears it
     * @return the machine as it now stands
     */
    Descriptor update(UUID uuid, String name, ConnectionDetails connection, MachineSpec spec);

    Stream<Descriptor> fetchAll();

    void delete(UUID uuid);
}
