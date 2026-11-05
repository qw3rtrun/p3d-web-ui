package org.qw3rtrun.p3d.identity;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.identity.service.ReactiveJdbcSessions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A signed-in user stays signed in across a restart - measured failing before the change, against
 * the running application: sign in, restart against the same database, the same cookie answers 401
 * (sessions lived in {@code InMemoryWebSessionStore}, so every deploy signed the whole farm out). A
 * test cannot restart a process, so it builds a second repository instance and asks that one, which
 * fails for an in-memory store for exactly the reason a restart does - the state was in the object,
 * not the database.
 */
@SpringBootTest
class SessionsSurviveARestartTest {

    @SpringBootApplication
    static class App {
    }

    @Autowired
    private ReactiveSessionRepository<?> sessions;

    @Autowired
    private JdbcOperations jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    /** A repository that shares nothing with the running one but the database - i.e. a restart. */
    private ReactiveSessionRepository<?> afterARestart() {
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.afterPropertiesSet();
        return ReactiveJdbcSessions.over(new JdbcIndexedSessionRepository(jdbc, template));
    }

    // Spring Session's JdbcSession is package-private, so the wildcard field type cannot be
    // captured by save directly; the cast is confined to this one method.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Mono<Void> save(ReactiveSessionRepository<?> repository, Session session) {
        return ((ReactiveSessionRepository) repository).save(session);
    }

    @Test
    void aSessionSavedByOneProcessIsFoundByTheNext() {
        var session = sessions.createSession().block();
        assertNotNull(session);
        session.setAttribute("who", "opal");
        save(sessions, session).block();

        StepVerifier.create(afterARestart().findById(session.getId()))
                .assertNext(found -> assertEquals("opal", found.getAttribute("who"),
                        "the session and its attributes should have come back from the database"))
                .verifyComplete();
    }

    /** An identifier nobody issued is simply absent, rather than an error. */
    @Test
    void anUnknownSessionIsEmptyAndNotAFailure() {
        StepVerifier.create(sessions.findById("00000000-0000-0000-0000-000000000000"))
                .verifyComplete();
    }

    /** Signing out removes it, and the next process agrees. */
    @Test
    void aDeletedSessionIsGoneForTheNextProcessToo() {
        var session = sessions.createSession().block();
        assertNotNull(session);
        save(sessions, session).block();

        sessions.deleteById(session.getId()).block();

        StepVerifier.create(afterARestart().findById(session.getId()))
                .verifyComplete();
    }

    // expiry and deletion are separate: the repository refuses an expired session immediately, and
    // the sweeper removes the row later.
    @Test
    void anExpiredSessionIsNotHandedBack() {
        var session = sessions.createSession().block();
        assertNotNull(session);
        // A *negative* interval means "never expires" in Spring Session, not "already expired" -
        // so the way to age a session out is a short interval and a last-accessed time in the past.
        session.setMaxInactiveInterval(Duration.ofSeconds(30));
        session.setLastAccessedTime(Instant.now().minus(Duration.ofHours(1)));
        save(sessions, session).block();

        StepVerifier.create(afterARestart().findById(session.getId()))
                .verifyComplete();
    }
}
