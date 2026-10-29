package org.qw3rtrun.p3d;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * The module boundary, asserted.
 *
 * <p>Spring Modulith was five dependencies and a logging level until this test existed: the
 * annotations describe a structure, and nothing checked that the code matched it. This is the check,
 * and it is the only reason the rest of the Modulith setup is worth having - a declared boundary
 * nobody verifies is a comment.
 *
 * <p>It is a plain unit test on purpose. It boots no Spring context and reads bytecode through
 * ArchUnit, so it costs a second and can run on every build rather than being something someone
 * remembers to do.
 *
 * <p><strong>What it enforces.</strong> That no module reaches into another module's nested
 * packages - those are internals, and the exported surface is the module's own package - and that
 * there are no cycles between modules. What it does not yet enforce is <em>which</em> module may
 * depend on which; that is {@code allowedDependencies} on each module's {@code @ApplicationModule},
 * and it is a tightening step worth taking once the modules stop moving (plan items B1 and C2 both
 * move one).
 */
class ApplicationModulesTest {

    /**
     * {@code :gcode} and {@code :backend:terminal}, excluded from the module model.
     *
     * <p>They live under this application's base package but are not part of the application. Both
     * are libraries with a hard rule against depending on Spring - {@code :gcode} must stay portable
     * to JS, C and Rust, and {@code :backend:terminal} is plain Reactor - so neither can carry a
     * Modulith annotation, and treating them as modules would mean either annotating them or
     * reporting every use of them as a violation.
     *
     * <p>Excluded, they are what they actually are: dependencies, like Reactor or Jackson, whose
     * internal structure is not this application's business. Their own rules are enforced by their
     * own skills and tests.
     */
    private static final DescribedPredicate<JavaClass> LIBRARIES =
            JavaClass.Predicates.resideInAnyPackage("org.qw3rtrun.p3d.g..", "org.qw3rtrun.p3d.terminal..");

    private final ApplicationModules modules = ApplicationModules.of(StandaloneApp.class, LIBRARIES);

    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }

    /**
     * Prints the structure the verification just checked.
     *
     * <p>Not an assertion - a listing. The module model is derived from package layout, so when a
     * package moves it is worth being able to see what Modulith now thinks the application is
     * without reasoning about it.
     */
    @Test
    void theStructureIsWhatItSaysItIs() {
        modules.forEach(System.out::println);
    }
}
