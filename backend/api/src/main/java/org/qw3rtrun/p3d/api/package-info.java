/**
 * The web module: REST and SSE over the farm and over one machine, and the per-printer runtime that
 * sits behind them.
 *
 * <p><strong>It depends on no other application module</strong>, and the empty
 * {@code allowedDependencies} says so as an enforced invariant rather than a hope. What it uses is
 * the shared vocabulary in {@code core} - which is exempt, being declared shared on
 * {@code StandaloneApp} - plus two libraries outside the module model entirely, {@code :gcode} and
 * {@code :backend:terminal}. It reaches the persistence side only through
 * {@code MachineManagementService}, an interface in {@code core}, and never through
 * {@code org.qw3rtrun.p3d.mng}.
 *
 * <p>That invariant is worth having declared because the obvious shortcut is to import
 * {@code PrinterEntity} the first time this module needs a column that {@code Descriptor} does not
 * carry. Plan item B2 adds exactly such columns; this makes the shortcut fail the build instead of
 * passing review.
 *
 * <p>{@code firmware} is a nested package and therefore an internal of this module. It holds what
 * {@code M115} and {@code M503} reported, which is state belonging to the aggregate rather than to
 * the web - so it moves out with {@code PrinterState} under plan item B1, and nothing outside should
 * come to depend on it in the meantime.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Web API",
        allowedDependencies = {})
package org.qw3rtrun.p3d.api;
