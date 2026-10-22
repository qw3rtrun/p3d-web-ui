/**
 * The application's messages: what has happened to a machine, and what one can be asked to do.
 *
 * <p>Both directions, deliberately in one exported surface. An event and a command are the same
 * kind of thing to a module that depends on this one - a value it either reacts to or sends - and
 * splitting them would mean two named interfaces that every module imports together.
 */
@org.springframework.modulith.NamedInterface("msg")
package org.qw3rtrun.p3d.api.msg;
