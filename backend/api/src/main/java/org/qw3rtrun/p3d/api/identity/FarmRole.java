package org.qw3rtrun.p3d.api.identity;

/**
 * What a person is allowed to do to a farm of heaters - plan item D3.
 *
 * <p>Three roles and not a permission matrix, because the farm has three genuinely different
 * audiences and the difference between them is about consequence rather than convenience:
 *
 * <ul>
 *   <li>{@link #VIEWER} may look. A wall display in a workshop is this, and it is the role that
 *       makes the farm stream safe to leave open on a screen nobody is standing at.</li>
 *   <li>{@link #OPERATOR} may run the machines - connect, heat, move, start and stop a print.
 *       This is the boundary the plan names: <em>"a printer is a heater, and an unauthenticated
 *       {@code M104 S300} is a safety problem rather than a data one"</em>.</li>
 *   <li>{@link #ADMIN} may change what the farm <em>is</em> - register and delete machines, and
 *       read the actuator, which carries the beans, the build and the module structure.</li>
 * </ul>
 *
 * <p><strong>Each user holds exactly one, and the order here is not a hierarchy the framework
 * enforces.</strong> Spring Security can be given a {@code RoleHierarchy} that makes ADMIN imply
 * OPERATOR implicitly; this application spells the implication out at each rule with
 * {@code hasAnyRole} instead. One bean fewer to misconfigure, and a reader of the filter chain can
 * see who may call an endpoint without first knowing a hierarchy exists somewhere else.
 */
public enum FarmRole {
    VIEWER,
    OPERATOR,
    ADMIN
}
