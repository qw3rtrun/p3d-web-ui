package org.qw3rtrun.p3d;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * The module boundary, asserted: Spring Modulith's annotations describe a structure, but nothing
 * checked that the code matched it until this test did. It boots no Spring context and reads
 * bytecode through ArchUnit, so it costs about a second and can run on every build.
 *
 * <p>It enforces that no module reaches into another module's nested packages (the exported surface
 * is the module's own package) and that there are no cycles between modules. It does not yet enforce
 * <em>which</em> module may depend on which - that is {@code allowedDependencies} on each module's
 * {@code @ApplicationModule}, a tightening step for once the modules stop moving (plan items B1, C2).
 */
class ApplicationModulesTest {

    /**
     * {@code :gcode} and {@code :backend:terminal} excluded from the module model: both live under
     * this application's base package but must not depend on Spring ({@code :gcode} stays portable
     * to JS, C and Rust; {@code :backend:terminal} is plain Reactor), so neither can carry a Modulith
     * annotation. Excluded, they are treated like any other dependency - their own rules are
     * enforced by their own skills and tests.
     */
    private static final DescribedPredicate<JavaClass> LIBRARIES =
            JavaClass.Predicates.resideInAnyPackage("org.qw3rtrun.p3d.g..", "org.qw3rtrun.p3d.terminal..");

    private final ApplicationModules modules = ApplicationModules.of(StandaloneApp.class, LIBRARIES);

    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }

    /** Not an assertion - prints the derived module structure, worth seeing whenever a package moves. */
    @Test
    void theStructureIsWhatItSaysItIs() {
        modules.forEach(System.out::println);
    }
}
