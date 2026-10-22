package org.qw3rtrun.p3d;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * The assembly: every backend module and the web UI in one process.
 *
 * <p>Also the place the <strong>module structure</strong> is declared, because this is the only
 * module that sees all of them.
 *
 * <p>{@code api} used to be marked {@code sharedModules} here, which exempts a module from the
 * dependency checks entirely. That is the wrong tool for a shared vocabulary whose packages are not
 * all equally shared: it is what let {@code core} call {@code printer-management}'s service, and
 * {@code frontend} reach into {@code core}, without the verification being able to say so. The
 * exemption is gone; {@code api} exports four named interfaces instead, and every module lists the
 * ones it uses.
 *
 * <p>What counts as an application module is the set of direct sub-packages of this one, which here
 * means {@code api}, {@code core}, {@code management} and {@code frontend}. Two more packages sit below it and
 * are deliberately <em>not</em> modules: {@code org.qw3rtrun.p3d.g} ({@code :gcode}) and
 * {@code org.qw3rtrun.p3d.terminal} ({@code :backend:terminal}). Both are libraries rather than
 * parts of the application - neither may depend on Spring at all, so neither can carry a Modulith
 * annotation - and {@code ApplicationModulesTest} excludes them explicitly. They are consumed the
 * way any third-party dependency is.
 */
@Modulithic(systemName = "p3d")
@SpringBootApplication
public class StandaloneApp {

    static void main(String[] args) {
        SpringApplication.run(StandaloneApp.class, args);
    }
}
