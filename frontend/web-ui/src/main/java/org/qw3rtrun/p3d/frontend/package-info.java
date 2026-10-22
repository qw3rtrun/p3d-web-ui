/**
 * The {@code frontend} submodel: the three ways a human reaches this system.
 *
 * <p>REST and SSE under {@code /api-printer} and {@code /api-manager}, the Thymeleaf controllers in
 * {@code mvc} that render the dashboard and the monitor page, and the Vue build that this Gradle
 * module also carries. One submodel, because they are one surface: the pages are served by the same
 * application that answers the fetches, and splitting them would mean a boundary with no rule on it.
 *
 * <p><strong>It depends on {@code core}, and should not.</strong> Rule 1 of
 * {@code doc/sub_boundaries.txt} says a submodel depends on {@code api} and nothing else, and the
 * controllers reach {@code PrinterReactorManager} directly. That dependency is declared here rather
 * than hidden, because a boundary relaxed the moment it is declared teaches nobody anything - this
 * way the check reports exactly one crossing, and the day it is closed the declaration becomes
 * {@code {}} and the check holds it there.
 *
 * <p>Closing it means an interface in {@code api} for what the controllers actually need - the
 * state, the update stream, and a way to hand a command to a machine. That is plan item A6, and the
 * thing to settle first is whether {@code api} is allowed to carry Reactor types, since every one of
 * those methods is a {@code Mono} or a {@code Flux}.
 *
 * <p>The management side is already right: {@code FDMPrinterManagementController} goes through
 * {@code MachineManagementService} in {@code api} and never names anything in {@code management}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Frontend",
        allowedDependencies = {"core"})
package org.qw3rtrun.p3d.frontend;
