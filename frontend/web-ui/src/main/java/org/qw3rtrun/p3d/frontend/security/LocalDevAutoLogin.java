package org.qw3rtrun.p3d.frontend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Local-run convenience: every request arrives already signed in as the administrator, so the API
 * can be called with no login step at all - issue #90.
 *
 * <p><strong>Active only under the {@code local} Spring profile, and under no other.</strong> It is
 * a {@code @Profile("local")} bean rather than a property switch, so it cannot be turned on by
 * accident in a deployment that merely forgets a flag - the profile itself has to be named. This
 * exists for one reason: a developer calling the API from a terminal or a tool that cannot drive
 * the browser's sign-in form, against a farm on their own machine.
 *
 * <p>It is wired into {@link SecurityConfiguration}'s own chain, at {@code AUTHENTICATION}'s
 * position, rather than left as a bare {@code @Bean WebFilter}. A plain global filter bean runs
 * after Spring Security's chain - that chain is itself one filter, registered at order {@code -100}
 * - so writing the security context from outside it is too late for {@code authorizeExchange} to
 * see the difference; every one of the non-local roles the farm is split by were tested against
 * this before the position was fixed, and the symptom in each case was a 401 or a redirect rather
 * than the admin principal.
 */
@Configuration
@Profile("local")
public class LocalDevAutoLogin {

    static final String USERNAME = "admin";

    @Bean
    public WebFilter localAdminAutoLogin() {
        var admin = new UsernamePasswordAuthenticationToken(
                USERNAME, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var context = Mono.just((SecurityContext) new SecurityContextImpl(admin));
        return (exchange, chain) -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(context));
    }
}
