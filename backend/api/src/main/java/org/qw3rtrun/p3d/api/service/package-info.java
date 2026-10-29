/**
 * The contract of {@code printer-management}: the farm's roster, as everyone else sees it.
 *
 * <p><strong>Its own named interface so that {@code core} can be denied it.</strong> Rule 2 of
 * {@code doc/sub_boundaries.txt} says {@code core} talks to other submodels through events, and
 * {@code core} keeps a replica rather than calling this - but while this lived in the same exported
 * surface as everything else, nothing stopped it going back to calling, and the verification could
 * not tell. Separated, {@code core}'s {@code allowedDependencies} simply does not list it.
 */
@org.springframework.modulith.NamedInterface("management")
package org.qw3rtrun.p3d.api.service;
