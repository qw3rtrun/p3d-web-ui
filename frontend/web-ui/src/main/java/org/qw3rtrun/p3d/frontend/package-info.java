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
 * <p>Closing that took an application command model. The obvious interface - "give me the runtime"
 * - could not be written, because what a controller wanted to send was a `:gcode` command class and
 * {@code api} must not depend on `:gcode`. So the request bodies are {@code PrinterCommand} records
 * now and {@code core} translates them into Marlin's, which is the outbound twin of the reply
 * bridge in {@code :backend:terminal}. The field names are the ones the browser already sent, so
 * the HTTP contract did not move - it merely stopped being defined by a code generator.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Frontend",
        allowedDependencies = {"api::model", "api::msg", "api::control", "api::management"})
package org.qw3rtrun.p3d.frontend;
