/**
 * The {@code frontend} submodel: the three ways a human reaches this system.
 *
 * <p>REST and SSE under {@code /api-printer} and {@code /api-manager}, the Thymeleaf controllers in
 * {@code mvc} that render the dashboard and the monitor page, and the Vue build that this Gradle
 * module also carries. One submodel, because they are one surface: the pages are served by the same
 * application that answers the fetches, and splitting them would mean a boundary with no rule on it.
 *
 * <p><strong>It depends on no other submodel</strong>, which is rule 1 of
 * {@code doc/sub_boundaries.txt} and is now enforced rather than reported. The controllers reach
 * the printer runtime through {@code PrinterControl} and the farm's roster through
 * {@code MachineManagementService}, both interfaces in {@code api}; neither {@code core} nor
 * {@code printer-management} is named anywhere here, and the Gradle build does not even put them on
 * this module's compile path.
 *
 * <p><strong>The request bodies live here, and they are the only thing that does.</strong>
 * {@code PrinterControl} speaks {@code :gcode} - a Marlin farm is coupled to G-code by
 * construction, so the runtime has no use for a second set of names for one set of facts - but an
 * HTTP contract is not a command set. What the browser posts to {@code /move} should go on being
 * {@code {"x":10,"relative":true}} whatever {@code G1}'s parameter list looks like this year, so
 * the {@code command} package below holds those shapes and each one translates itself. That is one
 * translation at the edge that receives the request, in place of the mirror of the whole protocol
 * that {@code api} used to carry.
 *
 * <p>{@code api::identity} joined with plan item D3, and it is the narrowest of the lot: this
 * submodel is the only place in the application where a request is authenticated, because it is the
 * only place a request exists. Who the people <em>are</em> belongs to {@code identity}, which owns
 * the table and the password hashing; what this module does is decide, per path, which of them may
 * call it.
 *
 * <p>{@code api::files} joined the list with plan item C1. The upload and download endpoints are
 * here because {@code doc/sub_boundaries.txt} puts every server API in this submodel, and because
 * the pooled {@code DataBuffer}s WebFlux hands out are this layer's to release - the store takes
 * plain {@code ByteBuffer}s, which is what keeps Spring out of {@code api}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Frontend",
        allowedDependencies = {"api::model", "api::msg", "api::control", "api::management",
                "api::files", "api::jobs", "api::identity"})
package org.qw3rtrun.p3d.frontend;
