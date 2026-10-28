package org.qw3rtrun.p3d.frontend;

import org.qw3rtrun.p3d.api.identity.FarmRole;
import org.qw3rtrun.p3d.api.identity.FarmUser;
import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A people roster for tests that boot this submodel alone.
 *
 * <p>Unlike the other collaborators in {@link BootVuejsApplicationTests.Collaborators}, this one
 * <strong>works</strong> rather than throwing. Those stub a service whose absence should be loud;
 * this stubs a store, and a store that throws would make every security test fail as an error
 * instead of as a 401 or a 403 - which is precisely the distinction those tests exist to draw.
 *
 * <p>It hashes with the same BCrypt the real directory uses, so a test signing in exercises the
 * actual password comparison rather than a string equality that would pass against a plaintext
 * column.
 */
public class InMemoryFarmUsers implements FarmUserDirectory {

    /** The password every test user has. Not a secret; this store never leaves test scope. */
    public static final String PASSWORD = "correct horse battery staple";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, FarmUser> users = new ConcurrentHashMap<>();

    public InMemoryFarmUsers() {
        add("viewer", PASSWORD, FarmRole.VIEWER);
        add("operator", PASSWORD, FarmRole.OPERATOR);
        add("admin", PASSWORD, FarmRole.ADMIN);
    }

    private static String fold(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public Optional<FarmUser> byName(String username) {
        return Optional.ofNullable(users.get(fold(username)));
    }

    @Override
    public List<FarmUser> all() {
        return List.copyOf(users.values());
    }

    @Override
    public FarmUser add(String username, String rawPassword, FarmRole role) {
        var user = new FarmUser(fold(username), encoder.encode(rawPassword), role, true);
        users.put(user.username(), user);
        return user;
    }

    @Override
    public void changePassword(String username, String rawPassword) {
        var existing = users.get(fold(username));
        users.put(existing.username(), new FarmUser(existing.username(),
                encoder.encode(rawPassword), existing.role(), existing.enabled()));
    }

    @Override
    public void delete(String username) {
        users.remove(fold(username));
    }

    @Override
    public boolean isEmpty() {
        return users.isEmpty();
    }
}
