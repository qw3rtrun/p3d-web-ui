package org.qw3rtrun.p3d.api.identity;

import java.util.List;
import java.util.Optional;

/**
 * Who may use this farm - the roster of people, as opposed to the roster of machines.
 *
 * <p><strong>Blocking, and deliberately so.</strong> Every other query contract in {@code api}
 * that is backed by JPA is written this way, and the rule that keeps it safe lives at the call
 * site: whatever reaches a method here from a web thread is responsible for
 * {@code subscribeOn(Schedulers.boundedElastic())}. The alternative - returning publishers from an
 * interface whose only implementation is blocking JDBC - would hide that obligation rather than
 * remove it.
 *
 * <p><strong>{@link #add} takes a raw password and no method ever returns one.</strong> Hashing
 * belongs to whoever owns the store, so a caller cannot choose a weak scheme, forget to hash, or
 * write a plaintext into the column. It is the one place in this application a cleartext password
 * legitimately exists.
 */
public interface FarmUserDirectory {

    /** The user by that name, or empty. Used to authenticate, so it must not distinguish absent from disabled. */
    Optional<FarmUser> byName(String username);

    List<FarmUser> all();

    /** Registers a user, hashing {@code rawPassword} on the way in. */
    FarmUser add(String username, String rawPassword, FarmRole role);

    /** Changes a user's password, hashing it on the way in. */
    void changePassword(String username, String rawPassword);

    void delete(String username);

    /** Whether anybody at all may log in - the question that decides whether a farm bootstraps an admin. */
    boolean isEmpty();
}
