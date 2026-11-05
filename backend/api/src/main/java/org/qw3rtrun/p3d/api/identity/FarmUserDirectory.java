package org.qw3rtrun.p3d.api.identity;

import java.util.List;
import java.util.Optional;

/**
 * Who may use this farm - the roster of people, as opposed to the roster of machines.
 *
 * <p>Blocking, deliberately: every other JPA-backed query contract in {@code api} is written this
 * way, and the obligation to move off the event loop lives at the call site
 * ({@code subscribeOn(Schedulers.boundedElastic())}) rather than being hidden behind publishers
 * whose only implementation is blocking JDBC.
 *
 * <p>{@link #add} takes a raw password and no method ever returns one - hashing belongs to
 * whoever owns the store, so a caller cannot choose a weak scheme, forget to hash, or write a
 * plaintext into the column. It is the one place in this application a cleartext password
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
