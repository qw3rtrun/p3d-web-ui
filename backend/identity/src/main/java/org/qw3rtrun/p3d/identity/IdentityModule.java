package org.qw3rtrun.p3d.identity;

import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.qw3rtrun.p3d.identity.service.FarmUserDirectoryImpl;
import org.qw3rtrun.p3d.identity.service.InitialAdmin;
import org.qw3rtrun.p3d.identity.service.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableJpaRepositories
public class IdentityModule {

    /**
     * BCrypt, and the one encoder the application has.
     *
     * <p>It is declared here rather than beside the security configuration because this module is
     * what writes hashes - the web layer only compares them, and a second encoder bean configured
     * differently from this one would produce a farm where new passwords cannot be verified.
     */
    @Bean
    public static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public static FarmUserDirectory farmUsers(UserRepository repository, PasswordEncoder encoder) {
        return new FarmUserDirectoryImpl(repository, encoder);
    }

    /**
     * The first account, created only when the roster is empty - see {@link InitialAdmin}.
     *
     * <p>Not {@code static}, unlike the two above: it takes {@code @Value} properties, and a static
     * {@code @Bean} method is resolved before the environment is available to inject into one.
     */
    @Bean
    public InitialAdmin initialAdmin(
            FarmUserDirectory users,
            @org.springframework.beans.factory.annotation.Value(
                    "${p3d.security.initial-admin.username:admin}") String username,
            @org.springframework.beans.factory.annotation.Value(
                    "${p3d.security.initial-admin.password:}") String password) {
        return new InitialAdmin(users, username, password);
    }
}
