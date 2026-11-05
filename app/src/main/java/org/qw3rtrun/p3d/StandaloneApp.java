package org.qw3rtrun.p3d;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * The assembly: every backend module and the web UI in one process, and the only module that sees
 * all of them, so the module structure is declared here.
 *
 * <p>An application module is a direct sub-package of this one: {@code api}, {@code core},
 * {@code management} and {@code frontend}. {@code org.qw3rtrun.p3d.g} ({@code :gcode}) and
 * {@code org.qw3rtrun.p3d.terminal} ({@code :backend:terminal}) sit below it too but are libraries,
 * not modules - neither may depend on Spring, so neither carries a Modulith annotation, and
 * {@code ApplicationModulesTest} excludes them explicitly.
 *
 * <p>{@code api} is not {@code sharedModules} (which exempts a module from dependency checks
 * entirely): that let {@code core} call {@code printer-management}'s service and {@code frontend}
 * reach into {@code core} without verification catching it. {@code api} now exports four named
 * interfaces instead, and every module lists the ones it uses.
 */
@Modulithic(systemName = "p3d")
@SpringBootApplication
public class StandaloneApp {

    static void main(String[] args) {
        SpringApplication.run(StandaloneApp.class, args);
    }
}
