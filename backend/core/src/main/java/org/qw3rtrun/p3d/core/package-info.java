/**
 * The {@code core} submodel: the printer aggregate, and the live runtime around it.
 *
 * <p>{@code PrinterState} is the aggregate - connection state, firmware, capabilities,
 * temperatures; {@code PrinterReactor} is one machine's running conversation (transport, command
 * queue, replies). This is where live data lives; static data about a printer - name, where to dial
 * it, what it can do - belongs to {@code management}, deliberately a different module.
 *
 * <p>Communication with other submodels is events (rule 2 of {@code doc/sub_boundaries.txt}):
 * {@link PrinterDirectory} is this module's own replica of where to dial each machine, fed by
 * {@code MRegisteredEvent}/{@code MDeletedEvent} and never by a query, so nothing here calls
 * {@code printer-management} or needs a database to start a printer. The verification holds that:
 * {@code allowedDependencies} below lists {@code model}, {@code msg} and {@code control} of
 * {@code api}'s four named interfaces, not {@code management}, so calling
 * {@code MachineManagementService} from here fails the build rather than waiting to be noticed.
 * {@code control} is listed because this module implements it -
 * {@link PrinterControlService} is how {@code frontend} reaches a machine without naming anything
 * here.
 *
 * <p>{@code firmware} is a nested, internal package: what {@code M115}/{@code M503} reported is the
 * aggregate's state and nobody else's.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Printer core",
        allowedDependencies = {"api::model", "api::msg", "api::control", "api::jobs",
                "api::files"})
package org.qw3rtrun.p3d.core;
