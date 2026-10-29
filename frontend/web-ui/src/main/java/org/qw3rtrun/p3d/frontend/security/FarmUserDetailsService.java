package org.qw3rtrun.p3d.frontend.security;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.identity.FarmUser;
import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The bridge between Spring Security's reactive contract and a blocking people roster.
 *
 * <p><strong>The {@code subscribeOn} is the whole reason this class exists</strong> rather than the
 * directory implementing {@code ReactiveUserDetailsService} directly. {@link FarmUserDirectory} is
 * JPA, and {@code ReactiveUserDetailsService} is called by the authentication manager on a netty
 * event loop - so without it every login would run a JDBC query on the thread serving every other
 * request. That is this repository's cardinal rule, and a login is a particularly bad place to
 * break it: it is the one request every user makes, and it happens when the farm is busiest at
 * shift change.
 *
 * <p>{@code Mono.fromCallable} and not {@code Mono.just}, for the same reason - {@code just}
 * evaluates its argument eagerly, on the calling thread, before any scheduler can move it.
 *
 * <p><strong>A missing user and a wrong password are the same answer.</strong> An empty
 * {@code Mono} here becomes a generic bad-credentials failure, so the login page cannot be used to
 * find out which usernames exist.
 */
@RequiredArgsConstructor
public class FarmUserDetailsService implements ReactiveUserDetailsService {

    private final FarmUserDirectory users;

    @Override
    public Mono<UserDetails> findByUsername(String username) {
        return Mono.fromCallable(() -> users.byName(username))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(found -> found.map(Mono::just).orElseGet(Mono::empty))
                .map(FarmUserDetailsService::describe);
    }

    /**
     * One role per user, handed to Spring Security as {@code ROLE_<name>}.
     *
     * <p>{@code roles(...)} adds the prefix; {@code authorities(...)} would not. Mixing the two up
     * produces a user whose role silently matches nothing, which reads as a permissions bug rather
     * than a wiring one.
     */
    private static UserDetails describe(FarmUser user) {
        return User.withUsername(user.username())
                .password(user.passwordHash())
                .roles(user.role().name())
                .disabled(!user.enabled())
                .build();
    }
}
