package org.qw3rtrun.p3d.printersmng.service;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.core.model.Printer;
import org.qw3rtrun.p3d.core.model.PrinterConnection;
import org.qw3rtrun.p3d.core.service.PrinterManager;
import org.qw3rtrun.p3d.printersmng.model.PrinterEntity;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class PrinterManagerImpl implements PrinterManager {
    private final PrinterRepository repo;

    public Printer fetch(UUID uuid) {
        return repo.findById(uuid).map(PrinterManagerImpl::map).orElseThrow();
    }

    @Transactional
    public Printer add(String name, PrinterConnection connection) {
        return map(repo.save(new PrinterEntity(UUID.randomUUID(), name, connection.host(), connection.port())));
    }

    public Stream<Printer> fetchAll() {
        return repo.findAll(Sort.by("name")).stream()
                .map(PrinterManagerImpl::map);
    }

    @Transactional
    @Override
    public void delete(UUID uuid) {
        repo.deleteById(uuid);
    }

    private static Printer map(PrinterEntity entity) {
        return new Printer(entity.getId(), entity.getName(),
                new PrinterConnection(entity.getHost(), entity.getPort()));
    }
}
