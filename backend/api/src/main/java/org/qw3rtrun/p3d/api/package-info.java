/**
 * The {@code api} submodel: the data classes, records and interfaces every other submodel is
 * written in.
 *
 * <p>Rule 1 of {@code doc/sub_boundaries.txt} - every submodel depends on this one and on no other.
 * It is declared {@code sharedModules} on {@code StandaloneApp}, which is what exempts it from the
 * dependency checks the other four are held to.
 *
 * <p><strong>An open module, deliberately.</strong> Modulith's default is that a module's nested
 * packages are internals and only its own package is exported - which is the right default for a
 * module that owns behaviour, and the wrong one here. There is nothing in {@code bus},
 * {@code model}, {@code msg} or {@code service} that is an implementation detail: this module is
 * the contract, and every type in it exists precisely so that another submodel can name it.
 * Declaring that as {@code OPEN} says so once, in the place the structure is described, rather than
 * repeating it as a {@code @NamedInterface} on each of the four nested packages and leaving the
 * next one to be forgotten.
 *
 * <p>What that gives up is the ability to make something here private to this module later. That is
 * a real cost and the right one to pay, because a type that wants to be private to the shared
 * vocabulary is a type that belongs in some other submodel.
 *
 * <p><strong>No Spring at runtime, and no dependency on anything.</strong> The annotation below is
 * the module's only Spring reference and is {@code compileOnly}: the declaration is read out of the
 * bytecode by {@code ApplicationModulesTest} in {@code :app}. Staying this light is not a
 * preference - {@code :gcode} depends on this module for the event interfaces its reply types
 * implement, and {@code :gcode} must remain portable to JS, C and Rust, so a Spring dependency here
 * would reach all the way into the protocol core.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        displayName = "Shared API")
package org.qw3rtrun.p3d.api;
