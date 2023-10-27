package org.qw3rtrun.p3d.core.service;

import org.qw3rtrun.p3d.core.model.Descriptor;
import org.qw3rtrun.p3d.core.model.ConnectionDetails;
import org.qw3rtrun.p3d.core.msg.EventMessage;

import java.util.UUID;
import java.util.stream.Stream;

public interface MachineDescriptionManager {

    Descriptor fetch(UUID uuid);

    Descriptor add(String name, ConnectionDetails connection);

    Stream<Descriptor> fetchAll();

    void delete(UUID uuid);

    void on(EventMessage event);
}
