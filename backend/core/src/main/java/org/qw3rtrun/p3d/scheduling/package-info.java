/**
 * The {@code scheduling} submodel: which queued job runs on which printer - plan item D1.
 *
 * <p><strong>It owns nothing.</strong> No table, no connection, no aggregate. What it has is a
 * rule, and everything the rule needs it asks for through {@code api}: the queue from
 * {@code api::jobs}, each machine's declared spec from {@code api::management}, and each machine's
 * live connection state from {@code api::control}.
 *
 * <p>That is why it is a module rather than a method on one of the others. Put it in {@code jobs}
 * and the queue has to know what a printer is; put it in {@code core} and the printer runtime has
 * to know what a queue is. Neither should, and the rule belongs to neither.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Scheduling",
        allowedDependencies = {"api::jobs", "api::management", "api::control", "api::model"})
package org.qw3rtrun.p3d.scheduling;
