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
 * <p><strong>The verification holds that now.</strong> {@code api} is a closed module exporting
 * four named interfaces, and this module's {@code allowedDependencies} lists three of them -
 * {@code model}, {@code msg} and {@code control}. It does not list {@code api::management}, so
 * going back to calling {@code MachineManagementService} from here is a failed build rather than a
 * comment someone has to notice.
 *
 * <p>{@code control} is in that list because this module <em>implements</em> it:
 * {@link PrinterControlService} is how {@code frontend} reaches a machine without naming anything
 * here.
 *
 * <p>{@code firmware} is a nested package and so an internal: it holds what {@code M115} and
 * {@code M503} reported, which is the aggregate's state and nobody else's.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Printer core",
        allowedDependencies = {"api::model", "api::msg", "api::control"})
package org.qw3rtrun.p3d.core;
