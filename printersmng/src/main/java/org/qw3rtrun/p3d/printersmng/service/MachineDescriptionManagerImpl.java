package org.qw3rtrun.p3d.printersmng.service;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.core.model.ConnectionDetails;
import org.qw3rtrun.p3d.core.model.Descriptor;
import org.qw3rtrun.p3d.core.msg.ConnectedEvent;
import org.qw3rtrun.p3d.core.msg.DisconnectedEvent;
import org.qw3rtrun.p3d.core.msg.EventMessage;
import org.qw3rtrun.p3d.core.service.MachineDescriptionManager;
import org.qw3rtrun.p3d.printersmng.model.PrinterEntity;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;


@RequiredArgsConstructor
public class MachineDescriptionManagerImpl implements MachineDescriptionManager {
    private final PrinterRepository repo;

    public Descriptor fetch(UUID uuid) {
        return repo.findById(uuid).map(MachineDescriptionManagerImpl::map).orElseThrow();
    }

    @Transactional
    public Descriptor add(String name, ConnectionDetails connection) {
        return map(repo.save(new PrinterEntity(UUID.randomUUID(), name, connection.host(), connection.port(), false)));
    }

    public Stream<Descriptor> fetchAll() {
        return repo.findAll(Sort.by("name")).stream()
                .map(MachineDescriptionManagerImpl::map);
    }

    @Transactional
    @Override
    public void delete(UUID uuid) {
        repo.deleteById(uuid);
    }

    @Override
    @Transactional
    public void on(EventMessage event) {
        switch (event.event()) {
            case ConnectedEvent c -> onConnect(event.id(), c);
            case DisconnectedEvent d -> onDisconnect(event.id(), d);
            default -> {
            }
        }
    }

    private void onDisconnect(UUID uuid, DisconnectedEvent d) {
        with(uuid, PrinterEntity::disconnect);
    }

    private void onConnect(UUID uuid, ConnectedEvent e) {
        with(uuid, PrinterEntity::connect);
    }

    private static Descriptor map(PrinterEntity entity) {
        return new Descriptor(entity.getId(), entity.getName(),
                new ConnectionDetails(entity.getHost(), entity.getPort()), entity.isConnected());
    }

    public void with(UUID id, Consumer<PrinterEntity> operation) {
        repo.findById(id).ifPresent(operation);
    }
}
