package org.qw3rtrun.p3d.core;



import java.util.UUID;

public interface PrinterAggregateManager {

    PrinterAggregate loadState(UUID uuid);
}
