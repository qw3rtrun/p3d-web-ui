package org.qw3rtrun.p3d.api.service;

import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;

import java.util.UUID;
import java.util.stream.Stream;

public interface MachineManagementService {

    Descriptor fetch(UUID uuid);

    Descriptor add(String name, ConnectionDetails connection);

    Stream<Descriptor> fetchAll();

    void delete(UUID uuid);
}
