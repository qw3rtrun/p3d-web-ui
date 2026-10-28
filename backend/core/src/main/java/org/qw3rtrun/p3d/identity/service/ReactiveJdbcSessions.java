package org.qw3rtrun.p3d.identity.service;

import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;

/**
 * Sessions kept in the database, behind the reactive contract WebFlux asks for.
 *
 * <p><strong>Why this class exists at all.</strong> Spring Session ships a reactive repository for
 * Redis and for MongoDB, and a blocking one for JDBC - there is no reactive JDBC implementation.
 * This farm has a database and no Redis, and adding a second piece of infrastructure to keep one
 * table would be a worse trade than the problem it solves. So the blocking repository is wrapped
 * rather than reimplemented: session storage is security-sensitive - identifiers, expiry, cascade
 * deletion - and Spring Session's JDBC implementation has had far more eyes on it than a hand-
 * rolled one would.
 *
 * <p><strong>The {@code subscribeOn} is the point of the wrapper</strong>, and it is this
 * repository's cardinal rule at its most load-bearing: a session is read on <em>every single
 * request</em>, so a JDBC call left on the event loop here would not be an occasional stall, it
 * would be the whole farm serialised behind one thread pool. {@code Mono.fromCallable} rather than
 * {@code Mono.just}, because {@code just} evaluates eagerly on the calling thread before any
 * scheduler can move the work.
 *
 * <p><strong>A missing session is an empty {@code Mono}, not a null.</strong>
 * {@code Mono.fromCallable} turns a {@code null} return into completion without a value, which is
 * exactly what {@link ReactiveSessionRepository} specifies for an unknown identifier - so an
 * expired cookie reads as "no session" rather than as an error.
 *
 * <p>The type parameter is inferred rather than written: Spring Session's {@code JdbcSession} is
 * package-private, so it cannot be named from here. {@link #over(SessionRepository)} exists so
 * that the compiler can infer what the caller cannot spell.
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
