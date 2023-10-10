package org.qw3rtrun.p3d.core.service;

import org.qw3rtrun.p3d.core.model.Printer;
import org.qw3rtrun.p3d.core.model.PrinterConnection;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public interface PrinterManager {

    Printer fetch(UUID uuid);

    Printer add(String name, PrinterConnection connection);

    Stream<Printer> fetchAll();

    void delete(UUID uuid);
}
