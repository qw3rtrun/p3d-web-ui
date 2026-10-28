package org.qw3rtrun.p3d.frontend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.web.server.session.SpringSessionWebSessionStore;
import org.springframework.web.server.session.DefaultWebSessionManager;
import org.springframework.web.server.session.WebSessionManager;

/**
 * Points WebFlux at the persistent session store, so a restart does not sign the farm out.
 *
 * <p><strong>Boot does not do this for you, and it fails silently.</strong> Spring Boot's reactive
 * session auto-configuration knows Redis and MongoDB; with a {@code ReactiveSessionRepository} of
 * any other kind it configures nothing, logs nothing, and WebFlux carries on with its default
 * {@code InMemoryWebSessionStore}. The symptom is that everything looks right - sessions work,
 * sign-in works, the database has the tables - and a restart still signs everybody out, which is
 * exactly how this was found: the fix was in place and the live check still answered 401.
 *
 * <p>So the manager is declared here. It lives in {@code frontend} because a
 * {@code WebSessionManager} is a WebFlux type and this is the submodel with the web layer; the
 * repository it is given comes from {@code identity}, through Spring Session's own interface, so
 * nothing here names that module.
 *
 * <p>The session cookie keeps its name and shape: {@code DefaultWebSessionManager}'s default
 * {@code WebSessionIdResolver} is a cookie called {@code SESSION}, which is what the store this
 * replaces used too - so an existing browser notices nothing beyond staying signed in.
 */
@Configuration
public class SessionStoreConfiguration {

    @Bean
    public WebSessionManager webSessionManager(ReactiveSessionRepository<?> sessions) {
        return managerOver(sessions);
    }

    /**
     * Captures the wildcard so the store can be constructed.
     *
     * <p>Spring Session's JDBC session type is package-private, so the repository can only be
     * injected as {@code ReactiveSessionRepository<?>}; a generic method is how the compiler infers
     * what cannot be written down here.
     */
    private static <S extends Session> WebSessionManager managerOver(
            ReactiveSessionRepository<S> sessions) {
        var manager = new DefaultWebSessionManager();
        manager.setSessionStore(new SpringSessionWebSessionStore<>(sessions));
        return manager;
    }
}
