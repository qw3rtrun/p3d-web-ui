/**
 * The {@code core} submodel: the printer aggregate, and the live runtime around it.
 *
 * <p>{@code PrinterState} is the aggregate - connection state, firmware, capabilities,
 * temperatures - and {@code PrinterReactor} is one machine's running conversation: the transport,
 * the command queue, and the replies coming back. This is where live data lives. Static data about
 * a printer - its name, where to dial it, what it can do - belongs to {@code management}, and the
 * two are deliberately different modules.
 *
 * <p><strong>Communication with other submodels is events.</strong> That is rule 2 of
 * {@code doc/sub_boundaries.txt}, and it is true now: {@link PrinterDirectory} is this module's own
 * replica of where to dial each machine, fed by {@code MRegisteredEvent} and {@code MDeletedEvent}
 * and never by a query. Nothing here calls {@code printer-management}, and nothing here needs a
 * database to start a printer.
 *
 * <p>The verification still cannot see that, because the only thing that would have broken the rule
 * - {@code MachineManagementService} - lives in the shared {@code api} module and so is invisible
 * to the dependency check. Making it enforceable means closing {@code api} and exporting its
 * packages as named interfaces, so this module can be denied the service package outright. That is
 * what remains of plan item A6, along with the {@code frontend} crossing below.
 *
 * <p>{@code firmware} is a nested package and so an internal: it holds what {@code M115} and
 * {@code M503} reported, which is the aggregate's state and nobody else's.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Printer core",
        allowedDependencies = {})
package org.qw3rtrun.p3d.core;
