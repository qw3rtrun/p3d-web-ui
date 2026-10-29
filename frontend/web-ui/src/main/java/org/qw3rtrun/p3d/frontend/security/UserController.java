package org.qw3rtrun.p3d.frontend.security;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.identity.FarmRole;
import org.qw3rtrun.p3d.api.identity.FarmUser;
import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Administering the people roster - plan item D3.
 *
 * <p><strong>Without this the three roles are decoration.</strong> The farm bootstraps exactly one
 * administrator and nothing else, so VIEWER and OPERATOR would be reachable only by somebody
 * writing to the database by hand - which means in practice everybody would share the admin
 * account, and the whole point of the item (that only an operator may heat a nozzle) would be
 * lost.
 *
 * <p><strong>No response here ever carries a password hash.</strong> {@link FarmUser} has one,
 * because the authentication path needs it; {@link Account} is what goes over the wire, and it
 * deliberately has no such field rather than relying on anybody remembering to strip it. The whole
 * surface is ADMIN-only, but a hash is not something to hand out even to an administrator - it is
 * an offline-cracking target, and it is not needed to manage an account.
 *
 * <p>Every method is a blocking JPA call moved to {@code boundedElastic}, which is this
 * repository's cardinal rule at a web boundary.
 */
@RestController
@RequestMapping(value = "/api-users", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class UserController {

    private final FarmUserDirectory users;

    /** A user as a browser sees one. No hash, by construction rather than by omission. */
    public record Account(String username, FarmRole role, boolean enabled) {
        static Account of(FarmUser user) {
            return new Account(user.username(), user.role(), user.enabled());
        }
    }

    /** What a browser sends to create one. The password exists only on the way in. */
    public record NewAccount(String username, String password, FarmRole role) {
    }

    /** And to rotate one. */
    public record NewPassword(String password) {
    }

    @GetMapping
    public Flux<Account> all() {
        return Mono.fromSupplier(users::all)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapIterable(found -> found.stream().map(Account::of).toList());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Account> add(@RequestBody Mono<NewAccount> body) {
        return body.flatMap(account -> Mono
                .fromSupplier(() -> users.add(account.username(), account.password(), account.role()))
                .subscribeOn(Schedulers.boundedElastic()))
                .map(Account::of);
    }

    @PutMapping(value = "/{username}/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> changePassword(@PathVariable String username,
                                     @RequestBody Mono<NewPassword> body) {
        return body.flatMap(password -> Mono
                .<Void>fromRunnable(() -> users.changePassword(username, password.password()))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @DeleteMapping("/{username}")
    public Mono<Void> delete(@PathVariable String username) {
        return Mono.<Void>fromRunnable(() -> users.delete(username))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
