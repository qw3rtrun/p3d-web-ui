/**
 * Who may use this farm, and what they are allowed to do to it - plan item D3.
 *
 * <p>A seventh named interface, for the reason the fifth and sixth existed: {@code api}'s packages
 * are split by who is allowed to use what. This one has exactly one consumer - the web layer, which
 * is the only place a request is authenticated - and one implementor, {@code identity}. No other
 * submodel has any business naming a user, and the boundary is what keeps it that way.
 *
 * <p>It names no framework. {@link org.qw3rtrun.p3d.api.identity.FarmUser} carries a password
 * <em>hash</em> as a {@code String}, not a Spring Security {@code UserDetails}, because {@code api}
 * must stay free of the frameworks that reach both the persistence side and the web side through
 * it.
 */
@org.springframework.modulith.NamedInterface("identity")
package org.qw3rtrun.p3d.api.identity;
