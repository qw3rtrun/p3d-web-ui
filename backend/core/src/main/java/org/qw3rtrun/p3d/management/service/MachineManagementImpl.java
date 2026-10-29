package org.qw3rtrun.p3d.management.service;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MCapabilityReportedEvent;
import org.qw3rtrun.p3d.api.msg.MConnectionChangedEvent;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MFirmwareReportedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.management.model.PrinterEntity;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class MachineManagementImpl implements MachineManagementService {

    private final MachineEventBus eventBus;
    private final PrinterRepository repo;

    public Descriptor fetch(UUID uuid) {
        return repo.findById(uuid).map(MachineManagementImpl::map).orElseThrow();
    }

    @Transactional
    public Descriptor add(String name, ConnectionDetails connection, MachineSpec spec) {
        var entity = new PrinterEntity(UUID.randomUUID(), name, connection.host(), connection.port());
        entity.declare(spec);
        var saved = repo.save(entity);
        eventBus.push(registered(saved));
        return map(saved);
    }

    /**
     * Tells everyone what the farm currently contains, once the application is up.
     *
     * <p>A module that keeps a replica of this data builds it from {@link MRegisteredEvent}, and a
     * replica is empty when the process starts - the events that filled it last time are long
     * delivered. So the rows are announced again here, which is why that event is worded as a
     * statement of fact rather than of a change.
     *
     * <p>This is the whole of the bootstrap story, and it is deliberately the dull version:
     * re-publishing current state beats replaying a log of changes, because the replica only ever
     * wants to know what is true now.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void announceTheFarm() {
        repo.findAll().forEach(entity -> eventBus.push(registered(entity)));
    }

    /**
     * Changes what a human declared, and tells everyone.
     *
     * <p><strong>It cannot touch the profile.</strong> An edit writes the spec columns and the
     * three reachability fields and nothing else, so what a firmware reported survives an edit
     * untouched and an edit cannot pass itself off as something the machine said - which is plan
     * item B4's "distinguishable" in the one place it could be broken.
     *
     * <p>Re-announced as {@link MRegisteredEvent}, like a firmware report and for the same reason:
     * {@code core}'s replica has to learn the new spec, and a declared extruder count is what
     * decides whether a command for tool 1 is refused. Without this an edit would reach the
     * database and not the running machine until a restart.
     */
    @Transactional
    @Override
    public Descriptor update(UUID uuid, String name, ConnectionDetails connection, MachineSpec spec) {
        var entity = repo.findById(uuid).orElseThrow();
        entity.reachableAs(name, connection.host(), connection.port());
        entity.declare(spec);
        var saved = repo.save(entity);
        eventBus.push(registered(saved));
        return map(saved);
    }

    private static MRegisteredEvent registered(PrinterEntity entity) {
        return new MRegisteredEvent(entity.getId(), entity.getName(),
                entity.getHost(), entity.getPort(), entity.profile(), entity.spec(),
                entity.connectionState(), LocalDateTime.now());
    }

    /**
     * A machine reported its firmware, so the roster remembers it.
     *
     * <p>This module cannot learn this for itself - it would have to talk to the printer, which is
     * {@code core}'s job and nobody else's - so the two halves meet here: {@code core} asks and
     * reports, this keeps and re-announces. That is why the fact travels as an event in one
     * direction and comes back inside {@link MRegisteredEvent} in the other.
     *
     * <p>Announced again straight away, so that a replica learns the new profile without waiting
     * for the next restart.
     */
    @Transactional
    @EventListener
    public void on(MFirmwareReportedEvent event) {
        repo.findById(event.machineId()).ifPresent(entity -> {
            entity.learnedFirmware(event.firmware(), event.timestamp());
            eventBus.push(registered(repo.save(entity)));
        });
    }

    /** The same, one {@code Cap:} line at a time - see {@link PrinterEntity#learnedCapability}. */
    @Transactional
    @EventListener
    public void on(MCapabilityReportedEvent event) {
        repo.findById(event.machineId()).ifPresent(entity -> {
            entity.learnedCapability(event.capability(), event.enabled(), event.timestamp());
            eventBus.push(registered(repo.save(entity)));
        });
    }

    /**
     * A machine's connection moved, so the roster writes it down.
     *
     * <p>The third fact that crosses this boundary, and the one that closes plan item B3's "the
     * entity's column is derived from it, not a parallel truth". Only {@code core} can know this -
     * knowing it means holding the socket - so the roster is told rather than asking, exactly as it
     * is told about firmware and capabilities.
     *
     * <p><strong>Deliberately not re-announced.</strong> A firmware report is announced back
     * because a replica has to learn what was kept; this came out of {@code core} and handing it
     * back would be a round trip whose only possible effect is a reconnect loop. What the
     * announcement does carry is the state as it stood at startup, which is what a restart reads.
     */
    @Transactional
    @EventListener
    public void on(MConnectionChangedEvent event) {
        repo.findById(event.getMachineId()).ifPresent(entity -> {
            entity.connectionChanged(event.getState());
            repo.save(entity);
        });
    }

    public Stream<Descriptor> fetchAll() {
        return repo.findAll(Sort.by("name")).stream()
                .map(MachineManagementImpl::map);
    }

    /**
     * Removes the row, and says so.
     *
     * <p>The event is what lets {@code core} let go of the machine: it may be holding an open
     * socket, a send window and a reply sink for a printer that has just stopped existing, and
     * nothing else would ever tell it. Published only when a row was actually removed, so a delete
     * of an unknown id stays the no-op it reads as.
     */
    @Transactional
    @Override
    public void delete(UUID uuid) {
        repo.findById(uuid).ifPresent(entity -> {
            repo.delete(entity);
            eventBus.push(new MDeletedEvent(uuid, LocalDateTime.now()));
        });
    }

    private static Descriptor map(PrinterEntity entity) {
        return new Descriptor(entity.getId(), entity.getName(),
                new ConnectionDetails(entity.getHost(), entity.getPort()), entity.connectionState(),
                entity.profile(), entity.spec());
    }
}
