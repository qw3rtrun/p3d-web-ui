/**
 * The {@code jobs} submodel: the farm's print queue and the state machine over it.
 *
 * <p><strong>It names no other submodel.</strong> A job holds a file id, and whether that id is
 * real is asked through {@code GcodeFileService} - an interface in {@code api} - rather than by
 * depending on {@code files} or by a foreign key between two modules' tables. It hears that a
 * machine was deleted through {@code MDeletedEvent}, the same way {@code core} does.
 *
 * <p>It is the first module to publish through Modulith's <strong>event publication registry</strong>,
 * so a job transition is written down inside the transaction that caused it and delivered at least
 * once afterwards. Plan item C3 will start a print on one of these events and C5's recovery depends
 * on them not being lost; an event dropped between the commit and the listener is a job that is
 * {@code RUNNING} in the database and running nowhere else.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Print jobs",
        allowedDependencies = {"api::jobs", "api::files", "api::msg"})
package org.qw3rtrun.p3d.jobs;
