package org.qw3rtrun.p3d.identity;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.identity.FarmRole;
import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The people roster, against the <strong>real Liquibase schema</strong> - plan item D3.
 *
 * <p>Against the real changelog rather than {@code ddl-auto}, like every other persistence test
 * here: a column this module's entity disagrees with should fail in a test and not on the morning
 * somebody deploys it. For a table holding credentials that matters more than most - the
 * {@code password_hash} column is deliberately far wider than BCrypt needs, and nothing but a
 * migration run would catch it if it were not.
 */
@SpringBootTest
class FarmUserDirectoryTest {

    @SpringBootApplication
    static class App {
    }

    @Autowired
    private FarmUserDirectory users;

    @Autowired
    private PasswordEncoder encoder;

    /**
     * What goes in the column is a hash, and the farm can still recognise the password.
     *
     * <p>Both halves matter. "It is not the plaintext" alone would pass against a store that
     * scrambled the password irrecoverably and could never log anybody in again.
     */
    @Test
    void aPasswordIsStoredHashedAndStillMatches() {
        var raw = "a long and unlikely passphrase";
        users.add("hashed", raw, FarmRole.OPERATOR);

        var stored = users.byName("hashed").orElseThrow();

        assertNotEquals(raw, stored.passwordHash(), "the column must never hold the password");
        assertFalse(stored.passwordHash().contains(raw), "nor contain it");
        assertTrue(encoder.matches(raw, stored.passwordHash()),
                "and the hash must still recognise the password it was made from");
    }

    /**
     * {@code Alice} and {@code alice} are one account.
     *
     * <p>Two would be a farm where revoking one leaves the other working, and where somebody who
     * capitalises their own name is told their password is wrong.
     */
    @Test
    void usernamesAreOneAccountWhateverTheCapitalisation() {
        users.add("Folded", "pw", FarmRole.VIEWER);

        assertTrue(users.byName("folded").isPresent(), "lower case should find it");
        assertTrue(users.byName("FOLDED").isPresent(), "upper case should find the same one");
        assertEquals("folded", users.byName("Folded").orElseThrow().username(),
                "and the stored spelling should be the folded one");
    }

    /** Registering the same person twice is refused rather than silently overwriting them. */
    @Test
    void aDuplicateUserIsRefused() {
        users.add("twice", "pw", FarmRole.VIEWER);

        var refused = assertThrows(IllegalArgumentException.class,
                () -> users.add("TWICE", "other", FarmRole.ADMIN));
        assertTrue(refused.getMessage().contains("twice"), refused.getMessage());
        assertEquals(FarmRole.VIEWER, users.byName("twice").orElseThrow().role(),
                "the original must be untouched - an overwrite here is a privilege escalation");
    }

    @Test
    void aChangedPasswordReplacesTheOldOne() {
        users.add("rotating", "first", FarmRole.OPERATOR);
        var before = users.byName("rotating").orElseThrow().passwordHash();

        users.changePassword("rotating", "second");
        var after = users.byName("rotating").orElseThrow().passwordHash();

        assertNotEquals(before, after);
        assertTrue(encoder.matches("second", after), "the new password should work");
        assertFalse(encoder.matches("first", after), "and the old one should not");
    }

    @Test
    void aDeletedUserIsGone() {
        users.add("temporary", "pw", FarmRole.VIEWER);
        users.delete("TEMPORARY");

        assertTrue(users.byName("temporary").isEmpty());
    }

    /** Nobody is a state the farm has to recognise - it is what decides whether an admin is made. */
    @Test
    void anEmptyRosterKnowsItIsEmpty() {
        assertEquals(users.isEmpty(), users.all().isEmpty(),
                "isEmpty must agree with the roster it reports on");
    }
}
