package org.qw3rtrun.p3d.identity.service;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.identity.FarmRole;
import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The first account, so that turning security on does not lock everybody out of their own farm.
 *
 * <p><strong>There is no default password, and that is the entire point of this class.</strong> A
 * farm that shipped {@code admin}/{@code admin} would be worse than the unauthenticated farm this
 * item replaces: it would look protected. So the password is either one an operator set
 * deliberately, or one nobody chose -
 *
 * <ul>
 *   <li>{@code p3d.security.initial-admin.password} set - that is used, and never logged;</li>
 *   <li>unset - 192 bits from {@link SecureRandom} are generated and written to the log
 *       <strong>once</strong>, at WARN, the way Spring Boot's own generated password is. It is not
 *       stored anywhere in recoverable form, so losing it means deleting the row and restarting.</li>
 * </ul>
 *
 * <p><strong>It runs only when the roster is empty.</strong> Not "when the admin is missing" - a
 * farm whose administrator deliberately deleted the default account must not have it recreated on
 * the next restart, and a farm with any user at all has somebody who can create more.
 *
 * <p>An {@link ApplicationRunner} rather than anything earlier, because it needs the schema to
 * exist: Liquibase runs during context refresh, and this runs after it has finished.
 */
@Slf4j
public class InitialAdmin implements ApplicationRunner {

    private final FarmUserDirectory users;
    private final String username;
    private final String configuredPassword;

    public InitialAdmin(FarmUserDirectory users,
                        String username,
                        String configuredPassword) {
        this.users = users;
        this.username = username;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!users.isEmpty()) {
            log.debug("the farm already has users; no initial administrator created");
            return;
        }
        var generated = configuredPassword == null || configuredPassword.isBlank();
        var password = generated ? generate() : configuredPassword;
        users.add(username, password, FarmRole.ADMIN);

        if (generated) {
            log.warn("""
                    
                    No users existed, so an administrator was created.
                    
                        username: {}
                        password: {}
                    
                    This password is shown once and is not recoverable. Change it after signing in,
                    or set p3d.security.initial-admin.password before first start.
                    """, username, password);
        } else {
            log.info("no users existed; created administrator '{}' from the configured password",
                    username);
        }
    }

    /**
     * 192 bits, URL-safe and unpadded so that it survives being copied out of a log.
     */
    private static String generate() {
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
