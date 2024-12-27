package org.qw3rtrun.p3d.core.service;

import org.qw3rtrun.p3d.core.model.ConnectionDetails;
import org.qw3rtrun.p3d.core.model.Descriptor;

import java.util.UUID;
import java.util.stream.Stream;

public interface MachineManagementService {

    Descriptor fetch(UUID uuid);

    Descriptor add(String name, ConnectionDetails connection);

    Stream<Descriptor> fetchAll();

    void delete(UUID uuid);

    void disconnect(UUID uuid);

    void connect(UUID uuid);
}
