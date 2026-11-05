package org.qw3rtrun.p3d.api.identity;

/**
 * What a person is allowed to do to a farm of heaters - plan item D3.
 *
 * <p>Three roles rather than a permission matrix, split by consequence rather than convenience:
 * {@link #VIEWER} may look; {@link #OPERATOR} may run the machines - connect, heat, move, start
 * and stop a print, the boundary that matters because an unauthenticated {@code M104 S300} is a
 * safety problem rather than a data one; {@link #ADMIN} may change what the farm <em>is</em> -
 * register and delete machines, and read the actuator.
 *
 * <p>Each user holds exactly one, and the order is not a hierarchy the framework enforces: rules
 * spell out {@code hasAnyRole} at each endpoint rather than relying on a {@code RoleHierarchy}
 * bean, so a reader of the filter chain can see who may call it without knowing one exists
 * elsewhere.
 */
public enum FarmRole {
    VIEWER,
    OPERATOR,
    ADMIN
}
