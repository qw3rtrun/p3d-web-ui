package org.qw3rtrun.p3d.core.service;

import org.qw3rtrun.p3d.core.model.PrinterAggregate;

import java.util.UUID;

public interface PrinterAggregateManager {

    PrinterAggregate loadState(UUID uuid);
}
