/**
 * The {@code api} submodel: the data classes, records and interfaces every other submodel is
 * written in.
 *
 * <p>Rule 1 of {@code doc/sub_boundaries.txt} - every submodel depends on this one and on no other,
 * which is true of all three now and enforced by their {@code allowedDependencies}.
 *
 * <p><strong>Closed, and exported as four named interfaces.</strong> It was open - every nested
 * package visible to everyone - which was true enough while the module was only records, and became
 * wrong the moment one of those packages was something a particular submodel had to be <em>denied</em>.
 * {@code service} is {@code printer-management}'s contract and {@code core} must not call it;
 * {@code control} is {@code core}'s contract and the web surface must; {@code model} and
 * {@code msg} are for everyone. Four surfaces, so each module's {@code allowedDependencies} can say
 * which it uses, and the verification holds it there.
 *
 * <p>That is also why this module is no longer declared {@code sharedModules} on
 * {@code StandaloneApp}: a shared module is exempt from the dependency checks, which is exactly the
 * exemption that let both of the old crossings hide.
 *
 * <p><strong>No Spring, and no framework.</strong> The Modulith annotation below is
 * {@code compileOnly} - the declaration is read out of the bytecode by
 * {@code ApplicationModulesTest} in {@code :app} - and the two dependencies it does have are not
 * frameworks: {@code org.reactivestreams}, the interop SPI that {@code Mono} and {@code Flux}
 * implement, and {@code :gcode}. That is deliberate rather than frugal: this is the vocabulary both
 * the web side and the persistence side are written in, so anything dragged in here is dragged into
 * both.
 *
 * <p><strong>Why {@code :gcode} is the exception.</strong> The dependency rule is
 * {@code backend -> terminal -> gcode}, and this module is where that chain surfaces: a reply
 * reaches a subscriber as the {@code GRs} the protocol decoded and a command leaves as a
 * {@code GRq}, rather than as a hand-maintained copy of each under a different name. {@code :gcode}
 * itself depends on nothing at all - it is a protocol library, not a framework, and it is the one
 * module here with no Spring anywhere near it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Shared API")
package org.qw3rtrun.p3d.api;
