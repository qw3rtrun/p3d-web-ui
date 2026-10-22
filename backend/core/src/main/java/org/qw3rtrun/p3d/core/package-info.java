/**
 * The {@code core} submodel: the printer aggregate, and the live runtime around it.
 *
 * <p>{@code PrinterState} is the aggregate - connection state, firmware, capabilities,
 * temperatures - and {@code PrinterReactor} is one machine's running conversation: the transport,
 * the command queue, and the replies coming back. This is where live data lives. Static data about
 * a printer - its name, where to dial it, what it can do - belongs to {@code management}, and the
 * two are deliberately different modules.
 *
 * <p><strong>Communication with other submodels is meant to be events.</strong> That is rule 2 of
 * {@code doc/sub_boundaries.txt}, and it is not true yet: {@code PrinterReactorManager} reaches
 * {@code management} synchronously through {@code MachineManagementService} to find out where to
 * dial. Because that interface lives in the shared {@code api} module, the boundary check cannot
 * see it, so this is a rule the verification does not enforce and a reader has to be told.
 *
 * <p>Closing it means this module keeping its own replica of the static data, fed by events from
 * {@code management} - which is plan item A6, and which is also what will let the check enforce the
 * rule: once nothing here calls a service, {@code api} can become a closed module with named
 * interfaces, and this one can be denied the service package outright.
 *
 * <p>{@code firmware} is a nested package and so an internal: it holds what {@code M115} and
 * {@code M503} reported, which is the aggregate's state and nobody else's.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Printer core",
        allowedDependencies = {})
package org.qw3rtrun.p3d.core;
