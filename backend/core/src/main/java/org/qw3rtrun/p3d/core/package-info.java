/**
 * The shared vocabulary: the records, service interfaces and event types every other module is
 * written in.
 *
 * <p><strong>An open module, deliberately.</strong> Modulith's default is that a module's nested
 * packages are internals and only its own package is exported - which is the right default for a
 * module that owns behaviour, and the wrong one here. There is nothing in {@code bus},
 * {@code model}, {@code msg} or {@code service} that is an implementation detail: this module is
 * the contract, and every type in it exists precisely so that another module can name it. Declaring
 * that as {@code OPEN} says so once, in the place the structure is described, rather than repeating
 * it as a {@code @NamedInterface} on each of the four nested packages and leaving the next one to
 * be forgotten.
 *
 * <p>What that gives up is the ability to make something here private to this module later. That is
 * a real cost and the right one to pay, because a type that wants to be private to the shared
 * vocabulary is a type that belongs in some other module.
 *
 * <p><strong>No Spring at runtime.</strong> The annotation below is the module's only Spring
 * reference and is {@code compileOnly}: the declaration is read out of the bytecode by
 * {@code ApplicationModulesTest} in {@code :app}. This module must stay loadable without a Spring
 * context, because both the web side and the persistence side depend on it and neither should
 * drag the other's framework in through it.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        displayName = "Shared domain")
package org.qw3rtrun.p3d.core;
