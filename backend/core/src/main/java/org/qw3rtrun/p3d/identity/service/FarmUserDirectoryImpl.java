package org.qw3rtrun.p3d.identity.service;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.identity.FarmRole;
import org.qw3rtrun.p3d.api.identity.FarmUser;
import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.qw3rtrun.p3d.identity.model.UserEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The people roster, over JPA.
 *
 * <p><strong>This is the only place in the application that sees a cleartext password</strong>, and
 * it sees one only as an argument: {@link #add} and {@link #changePassword} hash on the way in and
 * nothing here returns, stores or logs the raw value. That is the whole reason hashing lives in
 * this module rather than beside the security configuration - a contract that took a hash would
 * let any caller supply a bad one.
 *
 * <p>Blocking, like every other JPA-backed contract in {@code api}. Whatever calls it from a web
 * thread owes it a {@code subscribeOn(Schedulers.boundedElastic())}; see
 * {@code FarmUserDirectory}'s own note.
 */
@RequiredArgsConstructor
public class FarmUserDirectoryImpl implements FarmUserDirectory {

    private final UserRepository repository;
    private final PasswordEncoder encoder;

    /**
     * Usernames are compared folded to lower case, on write and on lookup alike.
     *
     * <p>{@code Locale.ROOT} and not the default locale: in a Turkish locale {@code "I"} folds to a
     * dotless {@code "\u0131"}, so the same name typed on two machines would reach two different
     * rows. A username is an identifier rather than prose, and identifiers do not get localised.
     */
    private static String fold(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FarmUser> byName(String username) {
        return repository.findById(fold(username)).map(UserEntity::describe);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FarmUser> all() {
        return repository.findAll().stream().map(UserEntity::describe).toList();
    }

    @Override
    @Transactional
    public FarmUser add(String username, String rawPassword, FarmRole role) {
        var name = fold(username);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a user needs a name");
        }
        if (repository.existsById(name)) {
            throw new IllegalArgumentException("there is already a user called " + name);
        }
        return repository.save(new UserEntity(name, encoder.encode(rawPassword), role)).describe();
    }

    @Override
    @Transactional
    public void changePassword(String username, String rawPassword) {
        var name = fold(username);
        var user = repository.findById(name)
                .orElseThrow(() -> new IllegalArgumentException("there is no user called " + name));
        user.setPasswordHash(encoder.encode(rawPassword));
        repository.save(user);
    }

    @Override
    @Transactional
    public void delete(String username) {
        repository.deleteById(fold(username));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return repository.count() == 0;
    }
}
