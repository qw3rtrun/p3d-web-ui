package org.qw3rtrun.p3d.management;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.msg.MSpecDeclaredEvent;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the roster says out loud when a person edits a machine, and what it deliberately does not
 * say when one is registered.
 *
 * <p>Two events for one edit is the fix for issue #93's fourth root cause rather than belt and
 * braces, so it is worth a test of its own: {@link MRegisteredEvent} is the announcement that
 * rebuilds {@code core}'s replica - idempotent, re-published about seven times per connect, each
 * copy carrying the spec as of its own read - and {@link MSpecDeclaredEvent} is the one edit,
 * published once, and the only thing that reaches a machine already running. While the running
 * machine learned its spec from the announcement, a straggler built before an edit put the old spec
 * back 1.5 ms after it and the next command was refused and never retried; see
 * {@code MSpecDeclaredEvent}'s KDoc for the capture. Dropping either push here is silent, so it is
 * asserted on both sides.
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class MachineEditAnnouncementTest {

    @Autowired
    private MachineManagementService roster;

    @Autowired
    private ApplicationEvents events;

    private static final ConnectionDetails SOMEWHERE = new ConnectionDetails("localhost", 8099);

    private static MachineSpec twoExtruders() {
        return new MachineSpec(null, 2, List.of(0.4, 0.6), Set.of("PLA"), Set.of());
    }

    @Test
    void anEditIsAnnouncedAndAlsoDeclared() {
        var machine = roster.add("a printer", SOMEWHERE, MachineSpec.undeclared());

        roster.update(machine.uuid(), "a printer", SOMEWHERE, twoExtruders());

        var declared = events.stream(MSpecDeclaredEvent.class)
                .filter(event -> event.getMachineId().equals(machine.uuid()))
                .toList();
        assertEquals(1, declared.size(),
                "an edit is a command to a live object and is published exactly once");
        assertEquals(2, declared.getFirst().getSpec().extruderCount());
        assertEquals(2, events.stream(MRegisteredEvent.class)
                        .filter(event -> event.getMachineId().equals(machine.uuid())).count(),
                "the announcement that rebuilds the replica is unchanged: one for the add, one for"
                        + " the edit");
    }

    /**
     * Registration mints the {@code UUID} in its own transaction, so nothing can be running under
     * it and a fresh aggregate is seeded from the replica at construction
     * ({@code CoreConfiguration.aggregates}). A declaration event here would have no reader.
     */
    @Test
    void registeringAMachineDeclaresNothingToAnybody() {
        var machine = roster.add("a printer", SOMEWHERE, twoExtruders());

        assertEquals(0, events.stream(MSpecDeclaredEvent.class)
                .filter(event -> event.getMachineId().equals(machine.uuid())).count());
        assertEquals(1, events.stream(MRegisteredEvent.class)
                .filter(event -> event.getMachineId().equals(machine.uuid())).count());
    }
}
