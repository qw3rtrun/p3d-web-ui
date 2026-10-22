package org.qw3rtrun.p3d.management.service;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.ManagementEvent;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.management.model.PrinterEntity;
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
        return map(repo.save(new PrinterEntity(UUID.randomUUID(), name, connection.host(), connection.port(), false)));
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
