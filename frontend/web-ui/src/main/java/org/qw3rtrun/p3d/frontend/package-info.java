/**
 * The {@code frontend} submodel: REST/SSE under {@code /api-printer} and {@code /api-manager}, the
 * Thymeleaf controllers in {@code mvc}, and the Vue build - one submodel because one application
 * serves all three, and splitting them would mean a boundary with no rule on it.
 *
 * <p>Depends on no other submodel (rule 1 of {@code doc/sub_boundaries.txt}, enforced by the Gradle
 * compile path, not just reported): the printer runtime and the roster are reached only through
 * {@code PrinterControl} and {@code MachineManagementService} in {@code api}.
 *
 * <p>The {@code command} package below holds the browser's request-body shapes and each one
 * translates itself to {@code :gcode} - keeping the HTTP contract independent of the Marlin
 * parameter list, in place of the protocol mirror {@code api} used to carry.
 *
 * <p>{@code api::identity} (plan item D3) is the narrowest dependency: this is the only submodel
 * where a request exists to authenticate, so it decides per path who may call in, while
 * {@code identity} itself owns the table and the password hashing. {@code api::files} (plan item
 * C1) covers upload and download, whose pooled {@code DataBuffer}s are this layer's to release -
 * the store takes plain {@code ByteBuffer}s, keeping Spring out of {@code api}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Frontend",
        allowedDependencies = {"api::model", "api::msg", "api::control", "api::management",
                "api::files", "api::jobs", "api::identity"})
package org.qw3rtrun.p3d.frontend;
