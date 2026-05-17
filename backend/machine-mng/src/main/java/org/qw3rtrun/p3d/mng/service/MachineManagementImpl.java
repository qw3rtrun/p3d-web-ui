package org.qw3rtrun.p3d.mng.service;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.core.model.ConnectionDetails;
import org.qw3rtrun.p3d.core.model.Descriptor;
import org.qw3rtrun.p3d.core.msg.ManagementEvent;
import org.qw3rtrun.p3d.core.service.MachineManagementService;
import org.qw3rtrun.p3d.mng.model.PrinterEntity;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    @Override
    public void delete(UUID uuid) {
        repo.deleteById(uuid);
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
