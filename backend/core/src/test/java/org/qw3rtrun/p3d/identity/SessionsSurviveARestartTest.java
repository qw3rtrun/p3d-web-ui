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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A signed-in user stays signed in across a restart.
 *
 * <p>This is the behaviour that was <strong>measured failing</strong> before the change, against
 * the running application: sign in, {@code GET /api-manager} answers 200; restart the process
 * against the same database; the same cookie answers <strong>401</strong>. Sessions lived in
 * {@code InMemoryWebSessionStore}, so every deploy signed the whole farm out.
 *
 * <p>A test cannot restart a process, so it does the thing that makes a restart lose a session:
 * it builds a <strong>second repository instance</strong> and asks that one. An in-memory store
 * fails that for exactly the reason a restart does - the state was in the object, not in the
 * database.
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

    /**
     * Saves through a repository whose session type cannot be named here.
     *
     * <p>Spring Session's {@code JdbcSession} is package-private, so the wildcard the field is
     * declared with cannot be captured by {@code save} directly. The cast is confined to this one
     * method rather than spread across the tests.
     */
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

    /**
     * A session the farm has expired is not handed back, even though its row may still be there.
     *
     * <p>Expiry and deletion are separate: the repository refuses an expired session immediately,
     * and the sweeper removes the row later. A farm that relied on the sweep for correctness would
     * honour a stale cookie for up to a minute.
     */
    @Test
    void anExpiredSessionIsNotHandedBack() {
        var session = sessions.createSession().block();
        assertNotNull(session);
        // A *negative* interval means "never expires" in Spring Session, not "already expired" -
        // so the way to age a session out is a short interval and a last-accessed time in the past.
        session.setMaxInactiveInterval(Duration.ofSeconds(30));
        session.setLastAccessedTime(java.time.Instant.now().minus(Duration.ofHours(1)));
        save(sessions, session).block();

        StepVerifier.create(afterARestart().findById(session.getId()))
                .verifyComplete();
    }
}
