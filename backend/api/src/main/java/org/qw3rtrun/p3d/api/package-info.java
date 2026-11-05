/**
 * The {@code api} submodel: the data classes, records and interfaces every other submodel is
 * written in.
 *
 * <p>Rule 1 of {@code doc/sub_boundaries.txt} - every submodel depends on this one and on no
 * other, enforced by their {@code allowedDependencies}.
 *
 * <p>Closed, exported as four named interfaces: {@code service} is {@code printer-management}'s
 * contract and {@code core} must not call it; {@code control} is {@code core}'s contract and the
 * web surface must; {@code model} and {@code msg} are for everyone. It was open before - every
 * nested package visible to all - which hid the moment a package needed to be <em>denied</em> to
 * one module. Not declared {@code sharedModules} either, since that exemption is what let the two
 * old crossings hide from verification.
 *
 * <p>No Spring, no framework: the Modulith annotation below is {@code compileOnly}, read out of
 * the bytecode by {@code ApplicationModulesTest} in {@code :app}. The two real dependencies are
 * {@code org.reactivestreams} (the interop SPI {@code Mono}/{@code Flux} implement) and
 * {@code :gcode} - deliberate, not frugal, since this vocabulary reaches both the web side and
 * the persistence side.
 *
 * <p>{@code :gcode} is the exception because the dependency rule is
 * {@code backend -> terminal -> gcode}, and this module is where that chain surfaces: a reply
 * reaches a subscriber as the {@code GRs} the protocol decoded and a command leaves as a
 * {@code GRq}, rather than a hand-maintained copy under a different name. {@code :gcode} itself
 * depends on nothing - a protocol library, with no Spring anywhere near it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Shared API")
package org.qw3rtrun.p3d.api;
