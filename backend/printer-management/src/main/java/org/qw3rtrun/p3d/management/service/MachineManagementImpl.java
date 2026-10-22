package org.qw3rtrun.p3d.management.service;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.msg.ManagementEvent;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.management.model.PrinterEntity;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class MachineManagementImpl implements MachineManagementService {

    private final MachineEventBus eventBus;
    private final PrinterRepository repo;

    public Descriptor fetch(UUID uuid) {
        return repo.findById(uuid).map(MachineManagementImpl::map).orElseThrow();
    }

    @Transactional
    public Descriptor add(String name, ConnectionDetails connection) {
        var saved = repo.save(new PrinterEntity(UUID.randomUUID(), name, connection.host(), connection.port(), false));
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

    private static MRegisteredEvent registered(PrinterEntity entity) {
        return new MRegisteredEvent(entity.getId(), entity.getName(),
                entity.getHost(), entity.getPort(), LocalDateTime.now());
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

    @Transactional
    @Override
    public void disconnect(UUID uuid) {
        with(uuid, PrinterEntity::disconnect);
    }

    @Transactional
    @Override
    public void connect(UUID uuid) {
        with(uuid, PrinterEntity::connect);
    }

    private static Descriptor map(PrinterEntity entity) {
        return new Descriptor(entity.getId(), entity.getName(),
                new ConnectionDetails(entity.getHost(), entity.getPort()), entity.isConnected());
    }

    public void with(UUID id, Consumer<PrinterEntity> operation) {
        repo.findById(id).ifPresent(operation);
    }

    public void with(UUID id, Function<PrinterEntity, ManagementEvent> operation) {
        repo.findById(id).ifPresent(m -> operation.andThen(eventBus::push).apply(m));
    }
}
