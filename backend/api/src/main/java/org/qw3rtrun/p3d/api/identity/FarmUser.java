package org.qw3rtrun.p3d.api.identity;

/**
 * Somebody who may use this farm, as every submodel sees them.
 *
 * <p><strong>The hash and never the password.</strong> There is no field here, and no method
 * anywhere in {@code api}, that carries a password in the clear: {@link FarmUserDirectory#add}
 * takes one and returns this, so a raw password exists only for the length of that call and cannot
 * be stored, logged or handed to another module by accident.
 *
 * <p>The hash is a plain {@code String} rather than any framework's type, because {@code api}
 * depends on nothing - including Spring Security. What produced it is the directory's business and
 * what compares it is the web layer's; this record only carries it between them.
 */
public record FarmUser(String username, String passwordHash, FarmRole role, boolean enabled) {
}
