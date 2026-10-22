/**
 * The contract of {@code core}: talking to one machine.
 *
 * <p>Exported separately from {@code service} because the two have opposite audiences - the web
 * surface needs this one, and {@code core} implements it, while {@code core} must not see the
 * other.
 */
@org.springframework.modulith.NamedInterface("control")
package org.qw3rtrun.p3d.api.control;
