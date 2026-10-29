package org.qw3rtrun.p3d.frontend.security;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * What every page needs to know about the person looking at it - plan item D3.
 *
 * <p><strong>Thymeleaf does not do this for you on WebFlux.</strong> On the servlet stack a
 * {@code RequestDataValueProcessor} injects the hidden field into every {@code th:action} form
 * without anybody asking, and almost every example of Spring Security with Thymeleaf relies on it.
 * There is no such processor in the reactive stack, so a form rendered without this is posted
 * without a token and rejected by the very filter that issued one - a 403 with no hint as to why,
 * on a page that looks completely normal.
 *
 * <p>One advice rather than a line in each controller, so that a page added later is not quietly
 * broken on its first POST.
 *
 * <p>Subscribing to the token is also what makes it <em>exist</em>: the repository writes the
 * cookie lazily, only when something asks for the token, so reading it here is what causes the
 * browser to receive one.
 */
@ControllerAdvice
public class PageModelAdvice {

    @ModelAttribute("_csrf")
    public Mono<CsrfToken> csrfToken(ServerWebExchange exchange) {
        Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
        return token == null ? Mono.empty() : token;
    }

    /**
     * Who is signed in, for the nav bar.
     *
     * <p>The theme this UI is built on ships a hardcoded "Allen Moreno" and a Sign Out link that
     * went nowhere. Both were harmless decoration while the farm had no users; with this item they
     * became actively misleading - a page that names somebody who is not you, beside a control
     * that claims to end a session it cannot.
     */
    @ModelAttribute("currentUser")
    public Mono<String> currentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication().getName())
                .defaultIfEmpty("");
    }

    /** The one role that user holds, without Spring Security's {@code ROLE_} prefix. */
    @ModelAttribute("currentRole")
    public Mono<String> currentRole() {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication().getAuthorities().stream()
                        .map(Object::toString)
                        .map(authority -> authority.startsWith("ROLE_")
                                ? authority.substring("ROLE_".length()) : authority)
                        .findFirst().orElse(""))
                .defaultIfEmpty("");
    }
}
