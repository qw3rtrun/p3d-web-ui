/**
 * The server-rendered pages: the dashboard, the add-printer form and the per-printer monitor.
 *
 * <p><strong>It depends on no other application module</strong>, and the empty
 * {@code allowedDependencies} enforces that. Like the web API it reaches management only through
 * {@code MachineManagementService} in the shared {@code core} module.
 *
 * <p>It lives in {@code :app} rather than in a module of its own because the Thymeleaf templates
 * and the static assets it renders are there too. That is a packaging convenience and not a
 * statement about the boundary - the boundary is this declaration, and the verification holds it
 * whichever Gradle module the files sit in.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Web UI",
        allowedDependencies = {})
package org.qw3rtrun.p3d.mvc;
