package org.qw3rtrun.p3d.api.service;

import org.qw3rtrun.p3d.api.model.PrinterAggregate;

import java.util.UUID;

public interface PrinterAggregateManager {

    PrinterAggregate loadState(UUID uuid);
}
