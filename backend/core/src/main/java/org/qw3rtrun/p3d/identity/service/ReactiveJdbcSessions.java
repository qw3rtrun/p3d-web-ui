package org.qw3rtrun.p3d.identity.service;

import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;

/**
 * Sessions kept in the database, behind the reactive contract WebFlux asks for. Spring Session ships
 * a reactive repository for Redis and MongoDB only and a blocking one for JDBC, so this wraps the
 * blocking one rather than reimplementing it: session storage is security-sensitive (identifiers,
 * expiry, cascade deletion) and the JDBC implementation has had far more eyes on it.
 *
 * <p>The {@code subscribeOn} is the point of the wrapper and this repository's cardinal rule at its
 * most load-bearing: a session is read on every single request, so a JDBC call left on the event
 * loop here would serialise the whole farm behind one thread pool, not just stall occasionally.
 * {@code Mono.fromCallable}, not {@code Mono.just}, since {@code just} evaluates eagerly on the
 * calling thread before any scheduler can move the work - and the same {@code fromCallable} turns a
 * {@code null} return into completion without a value, exactly what
 * {@link ReactiveSessionRepository} specifies for an unknown identifier, so an expired cookie reads
 * as "no session" rather than an error.
 *
 * <p>The type parameter is inferred rather than written, since Spring Session's {@code JdbcSession}
 * is package-private and cannot be named here; {@link #over(SessionRepository)} exists so the
 * compiler can infer what the caller cannot spell.
 */
public final class ReactiveJdbcSessions<S extends Session> implements ReactiveSessionRepository<S> {

    private final SessionRepository<S> delegate;

    private ReactiveJdbcSessions(SessionRepository<S> delegate) {
        this.delegate = delegate;
    }

    /** Wraps a blocking repository whose session type cannot be named at the call site. */
    public static <S extends Session> ReactiveJdbcSessions<S> over(SessionRepository<S> delegate) {
        return new ReactiveJdbcSessions<>(delegate);
    }

    @Override
    public Mono<S> createSession() {
        return offLoop(delegate::createSession);
    }

    @Override
    public Mono<Void> save(S session) {
        return offLoop(() -> {
            delegate.save(session);
            return null;
        }).then();
    }

    @Override
    public Mono<S> findById(String id) {
        return offLoop(() -> delegate.findById(id));
    }

    @Override
    public Mono<Void> deleteById(String id) {
        return offLoop(() -> {
            delegate.deleteById(id);
            return null;
        }).then();
    }

    private <T> Mono<T> offLoop(Callable<T> blocking) {
        return Mono.fromCallable(blocking).subscribeOn(Schedulers.boundedElastic());
    }
}
