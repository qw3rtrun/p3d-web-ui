package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.model.Descriptor;
import org.qw3rtrun.p3d.core.msg.ConnectCmd;
import org.qw3rtrun.p3d.core.service.MachineDescriptionManager;
import org.qw3rtrun.p3d.core.service.PrinterAggregateManager;
import org.qw3rtrun.p3d.terminal.ReactiveTerminal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class PrinterReactorManager {

    private final MachineDescriptionManager descriptionManager;

    private final PrinterAggregateManager aggregateManager;

    private final Map<UUID, PrinterReactor> running = new HashMap<>();

    public synchronized PrinterReactor getReactor(UUID uuid) {
        return running.computeIfAbsent(uuid, this::startReactor);
    }

    private PrinterReactor startReactor(UUID uuid) {
        var descriptor = descriptionManager.fetch(uuid);
        var aggregate = (PrinterState) aggregateManager.loadState(uuid);
        var reactor = new PrinterReactor(new ReactiveTerminal(descriptor.connection()), aggregate);
        reactor.updates().subscribe(descriptionManager::on);
        if (descriptor.connected()) {
            reactor.handleConnectCmd(new ConnectCmd(true));
        }
        return reactor;
    }
}
