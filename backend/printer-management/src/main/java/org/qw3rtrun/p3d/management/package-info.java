/**
 * The persistence module: printer rows, their repository, and the implementation of
 * {@code MachineManagementService} the rest of the application talks to.
 *
 * <p><strong>It depends on no other application module</strong>, and the empty
 * {@code allowedDependencies} enforces that. It implements an interface from the shared
 * {@code core} module and publishes its events through Spring, which is the whole of its outward
 * surface - nothing here is imported by name from anywhere else.
 *
 * <p>{@code model} and {@code service} are nested packages and so are internals by Modulith's
 * default, which is exactly right: {@code PrinterEntity} is a row and must not leave this module,
 * and a caller that wants one of those takes a {@code Descriptor} instead. The verification in
 * {@code :app} is what keeps that true.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Machine management",
        allowedDependencies = {"api::model", "api::msg", "api::management"})
package org.qw3rtrun.p3d.management;
