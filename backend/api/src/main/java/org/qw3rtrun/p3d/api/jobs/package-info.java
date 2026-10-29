/**
 * Print jobs: what one is, where it can go next, and what a caller gets when it may not go there.
 *
 * <p>A sixth named interface, for the reason the fifth existed: {@code api}'s packages are split by
 * who is allowed to use what, and a job is something {@code frontend} drives, {@code core} will run
 * (plan item C3) and a scheduler will assign (D1) - three modules with three different stakes in it.
 */
@org.springframework.modulith.NamedInterface("jobs")
package org.qw3rtrun.p3d.api.jobs;
