package org.qw3rtrun.p3d.identity;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.identity.FarmRole;
import org.qw3rtrun.p3d.api.identity.FarmUser;
import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.qw3rtrun.p3d.identity.service.InitialAdmin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first account - plan item D3. Mostly asserts what this class must not do: a default password
 * would look protected while being worse than no authentication, and recreating the administrator
 * on every restart would make deleting the account impossible.
 */
class InitialAdminTest {

    /** A roster that records what was asked of it, including the passwords - this is a test. */
    private static final class Roster implements FarmUserDirectory {
        private final List<FarmUser> users = new ArrayList<>();
        private final List<String> rawPasswords = new ArrayList<>();

        @Override
        public Optional<FarmUser> byName(String username) {
            return users.stream()
                    .filter(u -> u.username().equals(username.toLowerCase(Locale.ROOT)))
                    .findFirst();
        }

        @Override
        public List<FarmUser> all() {
            return List.copyOf(users);
        }

        @Override
        public FarmUser add(String username, String rawPassword, FarmRole role) {
            rawPasswords.add(rawPassword);
            var user = new FarmUser(username.toLowerCase(Locale.ROOT),
                    "hashed:" + rawPassword, role, true);
            users.add(user);
            return user;
        }

        @Override
        public void changePassword(String username, String rawPassword) {
        }

        @Override
        public void delete(String username) {
            users.removeIf(u -> u.username().equals(username));
        }

        @Override
        public boolean isEmpty() {
            return users.isEmpty();
        }
    }

    @Test
    void anEmptyFarmGetsAnAdministrator() {
        var roster = new Roster();

        new InitialAdmin(roster, "admin", "a configured password").run(null);

        var admin = roster.byName("admin").orElseThrow();
        assertEquals(FarmRole.ADMIN, admin.role());
        assertEquals(List.of("a configured password"), roster.rawPasswords,
                "a configured password should be used as given");
    }

    // not "a farm that has no admin" - a deliberately deleted default account must not come back on
    // restart, and any farm with a user has somebody who can create more.
    @Test
    void aFarmThatHasAnybodyAtAllIsLeftAlone() {
        var roster = new Roster();
        roster.add("someone", "their password", FarmRole.VIEWER);
        roster.rawPasswords.clear();

        new InitialAdmin(roster, "admin", "would be a backdoor").run(null);

        assertTrue(roster.byName("admin").isEmpty(), "no administrator should have been created");
        assertEquals(List.of(), roster.rawPasswords);
    }

    // assertions are deliberately about what it is not: the failure guarded against is somebody
    // replacing the generator with a constant.
    @Test
    void anUnconfiguredPasswordIsGeneratedAndNotADefault() {
        var first = new Roster();
        var second = new Roster();

        new InitialAdmin(first, "admin", "").run(null);
        new InitialAdmin(second, "admin", null).run(null);

        var one = first.rawPasswords.getFirst();
        var two = second.rawPasswords.getFirst();

        assertNotEquals(one, two, "two farms must not come up with the same password");
        assertTrue(one.length() >= 32, "a generated password should be long: " + one.length());
        assertFalse(one.equalsIgnoreCase("admin"), "the obvious default must not be it");
        assertFalse(one.isBlank());
    }

    /** A blank configured password is treated as "none set" rather than as the password. */
    @Test
    void aBlankConfiguredPasswordIsNotUsedAsThePassword() {
        var roster = new Roster();

        new InitialAdmin(roster, "admin", "   ").run(null);

        assertNotEquals("   ", roster.rawPasswords.getFirst(),
                "whitespace must not become somebody's password");
    }

    /** The name is configurable, because "admin" is the first thing anybody tries. */
    @Test
    void theAdministratorsNameIsConfigurable() {
        var roster = new Roster();

        new InitialAdmin(roster, "farm-keeper", "pw").run(null);

        assertTrue(roster.byName("farm-keeper").isPresent());
        assertTrue(roster.byName("admin").isEmpty());
    }
}
