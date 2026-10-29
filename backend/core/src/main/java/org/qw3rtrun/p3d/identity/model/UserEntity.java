package org.qw3rtrun.p3d.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.qw3rtrun.p3d.api.identity.FarmRole;
import org.qw3rtrun.p3d.api.identity.FarmUser;

/**
 * One person who may use this farm.
 *
 * <p>Internal to {@code identity} and must not leave it, the way {@code PrinterEntity} must not
 * leave {@code printer-management}: what crosses a boundary is {@link FarmUser}.
 *
 * <p><strong>The username is the key, folded to lower case.</strong> A farm where {@code Alice} and
 * {@code alice} are two accounts is a farm where revoking one leaves the other working, and
 * somebody typing their own name with the wrong capitalisation is told their password is wrong.
 * Folding happens in the directory, on every write and every lookup, so the column holds one
 * spelling and nothing has to remember to normalise.
 *
 * <p><strong>The role is stored as its name, not its ordinal.</strong> {@code EnumType.ORDINAL}
 * would make inserting a role into the middle of {@link FarmRole} silently promote every existing
 * user - a VIEWER becoming an OPERATOR because somebody tidied an enum.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserEntity {

    @Id
    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 256)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private FarmRole role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public UserEntity(String username, String passwordHash, FarmRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
    }

    /** The boundary-crossing view of this row. */
    public FarmUser describe() {
        return new FarmUser(username, passwordHash, role, enabled);
    }
}
