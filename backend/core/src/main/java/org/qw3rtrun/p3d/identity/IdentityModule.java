package org.qw3rtrun.p3d.identity;

import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.qw3rtrun.p3d.identity.service.FarmUserDirectoryImpl;
import org.qw3rtrun.p3d.identity.service.ExpiredSessionSweeper;
import org.qw3rtrun.p3d.identity.service.InitialAdmin;
import org.qw3rtrun.p3d.identity.service.ReactiveJdbcSessions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.qw3rtrun.p3d.identity.service.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

@Configuration
@EnableJpaRepositories
public class IdentityModule {

    /** BCrypt, the one encoder the application has - declared here, not beside the security configuration, because this module writes hashes and the web layer only compares them; a second, differently-configured encoder bean would make new passwords unverifiable. */
    @Bean
    public static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public static FarmUserDirectory farmUsers(UserRepository repository, PasswordEncoder encoder) {
        return new FarmUserDirectoryImpl(repository, encoder);
    }

    /**
     * Where signed-in sessions are kept - the fix for "a restart signs everybody out". Wired by hand
     * because Spring Session's own configuration targets the servlet stack and its reactive side
     * ships Redis/MongoDB only (see {@link ReactiveJdbcSessions} for why the blocking repository is
     * wrapped rather than a JDBC one written). Wiring by hand means inheriting what that
     * configuration would otherwise do for you, which is why {@link ExpiredSessionSweeper} exists.
     */
    @Bean
    public JdbcIndexedSessionRepository jdbcSessions(
            JdbcOperations jdbc,
            PlatformTransactionManager transactions,
            @Value("${p3d.security.session-timeout:8h}") Duration timeout) {
        var sessions = new JdbcIndexedSessionRepository(jdbc, transactionsFor(transactions));
        sessions.setDefaultMaxInactiveInterval(timeout);
        return sessions;
    }

    /** Matches Spring Session's own propagation: {@code REQUIRES_NEW}, since a session is saved at the end of a request that may already be in its own transaction, and joining it would tie staying signed in to whether that unrelated work commits. */
    private static TransactionTemplate transactionsFor(
            PlatformTransactionManager manager) {
        var template = new TransactionTemplate(manager);
        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * The reactive view of the above, which is what WebFlux resolves a session through. Eight hours
     * by default, not Spring Session's thirty minutes: the thing watching this farm all day is a
     * wall display, and signing it out twice an hour would teach whoever walks past to leave a
     * browser logged in permanently - shorten with {@code p3d.security.session-timeout} where that
     * trade does not hold.
     */
    @Bean
    public ReactiveSessionRepository<?> sessions(JdbcIndexedSessionRepository jdbcSessions) {
        return ReactiveJdbcSessions.over(jdbcSessions);
    }

    /** Expired rows have an owner that deletes them - see {@link ExpiredSessionSweeper}. */
    @Bean
    public ExpiredSessionSweeper expiredSessionSweeper(JdbcIndexedSessionRepository jdbcSessions) {
        return new ExpiredSessionSweeper(jdbcSessions);
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
            @Value(
                    "${p3d.security.initial-admin.username:admin}") String username,
            @Value(
                    "${p3d.security.initial-admin.password:admin}") String password) {
        return new InitialAdmin(users, username, password);
    }
}
