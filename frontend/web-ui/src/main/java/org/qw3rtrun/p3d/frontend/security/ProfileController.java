package org.qw3rtrun.p3d.frontend.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;

/**
 * A person's own account: who they are, and changing their own password.
 *
 * <p><strong>This exists because {@code /api-users} is ADMIN-only</strong>, and deliberately so -
 * reading the list of accounts is reconnaissance and creating one is privilege granting. But that
 * left a farm where a viewer or an operator could not change their own password at all, and the
 * only account that could was the one everybody would then end up sharing. This is the narrow hole
 * in that rule: <em>your own</em> password, and nothing else.
 *
 * <p><strong>The current password is verified, and that is not ceremony.</strong> Without it, a
 * session someone walked away from - or a CSRF hole, or a stolen cookie - is not merely a session,
 * it is the account: whoever has it sets a new password and the owner is locked out. Requiring the
 * old one means a borrowed session expires on its own rather than becoming permanent.
 *
 * <p>It is a Thymeleaf form rather than a Vue island because nothing on it keeps changing; see the
 * {@code vue-ui-dev} skill on which side of the architecture a thing belongs on.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ProfileController {

    private final FarmUserDirectory users;
    private final PasswordEncoder encoder;

    /** What a browser posts. The two fields exist only for the length of this request. */
    public record PasswordChange(String currentPassword, String newPassword, String confirmation) {
    }

    @GetMapping("/profile")
    public Mono<String> profile(Mono<Principal> principal, Model model) {
        return principal
                .doOnNext(who -> model.addAttribute("username", who.getName()))
                .thenReturn("profile.html");
    }

    /**
     * Changes the signed-in user's password.
     *
     * <p>Answers with a redirect carrying a result in the query string, the way the login page
     * does, so a refresh does not re-post the form.
     *
     * <p><strong>Every failure says the same thing it would have said anyway</strong>, except the
     * two the user can see for themselves - a mismatched confirmation and an empty password. A
     * wrong current password is reported as exactly that, because the person is already
     * authenticated: there is nothing left to disclose.
     */
    @PostMapping("/profile/password")
    public Mono<String> changePassword(Mono<Principal> principal,
                                       @ModelAttribute PasswordChange form) {
        if (form.newPassword() == null || form.newPassword().isBlank()) {
            return Mono.just("redirect:/profile?error=empty");
        }
        if (!form.newPassword().equals(form.confirmation())) {
            return Mono.just("redirect:/profile?error=mismatch");
        }
        return principal
                .map(Principal::getName)
                .flatMap(name -> Mono.fromCallable(() -> apply(name, form))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * The blocking half, on a thread that is allowed to block.
     *
     * <p>Two JPA calls - read the hash, then write a new one - which is the whole reason this is
     * off the event loop.
     */
    private String apply(String username, PasswordChange form) {
        var user = users.byName(username).orElse(null);
        if (user == null) {
            // The session outlived the account. Nothing to change, and nothing useful to say.
            log.warn("'{}' has a session but no account", username);
            return "redirect:/logout";
        }
        if (!encoder.matches(form.currentPassword(), user.passwordHash())) {
            return "redirect:/profile?error=wrong";
        }
        users.changePassword(username, form.newPassword());
        log.info("'{}' changed their password", username);
        return "redirect:/profile?changed";
    }
}
