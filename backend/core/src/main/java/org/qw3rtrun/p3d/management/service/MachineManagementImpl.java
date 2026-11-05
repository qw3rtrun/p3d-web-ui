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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class MachineManagementImpl implements MachineManagementService {

    private final MachineEventBus eventBus;
    private final PrinterRepository repo;

    public Optional<Descriptor> fetch(UUID uuid) {
        return repo.findById(uuid).map(MachineManagementImpl::map);
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
     * Tells everyone what the farm currently contains, once the application is up - a replica fed by
     * {@link MRegisteredEvent} starts empty every process, so the rows are announced again rather
     * than replaying a log of changes, which is why that event is worded as current fact rather than
     * a change.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void announceTheFarm() {
        repo.findAll().forEach(entity -> eventBus.push(registered(entity)));
    }

    /**
     * Changes what a human declared, and tells everyone. Cannot touch the profile - an edit writes
     * only the spec columns and the three reachability fields, so what a firmware reported survives
     * untouched (plan item B4's "distinguishable" in the one place it could be broken). Re-announced
     * as {@link MRegisteredEvent} so {@code core}'s replica learns the new spec at once - without
     * this an edit would reach the database and not the running machine until a restart.
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
     * A machine reported its firmware, so the roster remembers it - this module cannot learn it for
     * itself, since that means talking to the printer, {@code core}'s job alone, so the fact travels
     * in as an event and back out inside {@link MRegisteredEvent} so a replica learns it at once.
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
     * A machine's connection moved, so the roster writes it down - closing plan item B3's "the
     * entity's column is derived from it, not a parallel truth", since only {@code core} can know
     * this. Deliberately not re-announced, unlike firmware: this came from {@code core}, and handing
     * it back would only risk a reconnect loop. A restart instead reads the state as it stood then,
     * carried in the next {@link MRegisteredEvent}.
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

    /** Removes the row, and says so - the event is what lets {@code core} let go of a socket, send window and reply sink nothing else would ever tell it to release. Published only when a row was actually removed. */
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
