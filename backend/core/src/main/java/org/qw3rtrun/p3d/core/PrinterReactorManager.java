package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.service.PrinterAggregateManager;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import org.springframework.context.event.EventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The running printers, and the thing that owns them.
 *
 * <p>A reactor is a <strong>resource</strong>, not a value: it holds a TCP connection, a send
 * window, a command queue and a replay sink that keeps every SSE subscriber referenced. This map
 * used to only ever grow - {@code computeIfAbsent} and nothing else - so deleting a printer through
 * the management API left all of that behind, connected to a machine the farm no longer knew about.
 * Over a long-running process that is a socket and a sink per printer ever registered.
 */
@Slf4j
@RequiredArgsConstructor
public class PrinterReactorManager {

    private final MachineManagementService machines;

    private final PrinterAggregateManager aggregateManager;

    private final Map<UUID, PrinterReactor> running = new HashMap<>();

    /**
     * The printer's runtime, started if this is the first caller.
     *
     * <p><strong>Called {@code open} and not {@code get} because it is not a getter.</strong> On a
     * miss it reads the printer's row, builds a reactor, and - if the row says the printer was
     * connected - opens a socket to it. A name that promised a lookup made that side effect
     * invisible at every call site, and the call sites are REST handlers.
     *
     * <p>It pairs with {@link #release(UUID)}: whatever this opens, that closes.
     *
     * <p>The reconnect-on-first-access is close to dead code today, and worth knowing before
     * relying on it: nothing in production ever sets the row's {@code connected} flag.
     * {@code PrinterController.connect} goes to the reactor, not to the management service, so the
     * column is written {@code false} at creation and never again. Plan item B3 owns that.
     */
    public synchronized PrinterReactor openReactor(UUID uuid) {
        return running.computeIfAbsent(uuid, this::startReactor);
    }

    /**
     * Lets go of a printer's runtime: stops it, and forgets it.
     *
     * <p>Idempotent, and a no-op for a printer that was never opened - a delete of a machine nobody
     * ever talked to has nothing to release, and should not have to know that.
     */
    public synchronized void release(UUID uuid) {
        var reactor = running.remove(uuid);
        if (reactor == null) {
            return;
        }
        log.info("releasing the runtime of {}", uuid);
        reactor.stop();
    }

    /**
     * A machine was removed from the farm, so its runtime goes with it.
     *
     * <p>An event and not a call, because {@code management} must not reach into this module - and
     * because the two facts are genuinely separate: the row is gone whether or not anything was
     * holding a socket to it.
     *
     * <p>A plain {@link EventListener} rather than a transactional one. The transactional variant
     * would only release after the delete commits, which is tidier, but it silently does nothing
     * when no transaction is active - a failure mode that would reappear as a leaked socket the
     * next time someone published this event from somewhere else. Releasing slightly early is the
     * cheaper mistake: a rolled-back delete leaves a printer whose runtime is closed, and the next
     * {@link #openReactor(UUID)} builds a new one.
     */
    @EventListener
    public void onDeleted(MDeletedEvent event) {
        release(event.getMachineId());
    }

    private PrinterReactor startReactor(UUID uuid) {
        var descriptor = machines.fetch(uuid);
        var aggregate = (PrinterState) aggregateManager.loadState(uuid);
        var reactor = new PrinterReactor(new TerminalManager(descriptor.connection()), aggregate);
        if (descriptor.connected()) {
            reactor.handleConnectCmd(new ConnectCmd(true));
        }
        return reactor;
    }
}
