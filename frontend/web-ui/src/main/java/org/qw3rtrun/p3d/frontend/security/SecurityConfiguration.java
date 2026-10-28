package org.qw3rtrun.p3d.frontend.security;

import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * Who may do what to a farm of heaters - plan item D3.
 *
 * <p>The plan's reason for this item is a safety one rather than a data one: <em>a printer is a
 * heater, and an unauthenticated {@code M104 S300} is a safety problem</em>. So the rules below are
 * written around consequence - looking, running a machine, and changing what the farm is - and the
 * default is {@code authenticated()}, so a path nobody thought about is closed rather than open.
 *
 * <p><strong>Why the configuration is here and the users are not.</strong> Authenticating a
 * <em>request</em> needs a request, and this submodel is the only one that has one. Who the people
 * are belongs to {@code identity}, which this reaches through {@code FarmUserDirectory} in
 * {@code api} and never names directly.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

    /**
     * Everything a browser must fetch before anyone can log in.
     *
     * <p><strong>{@code /assets/**} is the built UI bundle and has to be here</strong>, because the
     * login page itself is styled by {@code /assets/p3d-ui.css}. It carries no data - it is the
     * same stylesheet and the same JavaScript every visitor would get anyway - and the endpoints
     * it talks to are each guarded on their own.
     *
     * <p>{@code /css/**}, {@code /js/**}, {@code /vendors/**} and {@code /pages/**} used to be
     * here and are gone with the directories they named: the theme is bundled now, and what
     * {@code /pages/**} served was dead demo markup nothing linked to.
     */
    private static final String[] PUBLIC = {
            "/login", "/assets/**", "/images/**", "/webjars/**", "/favicon.ico"
    };

    /** Reading the farm: the dashboard, the monitor page, and every stream and query behind them. */
    private static final String[] READ_PAGES = {"/", "/index.html", "/dashboard.html", "/printers/*"};

    /** Changing what the farm *is*: registering a machine, and the pages that do it. */
    private static final String[] ADMIN_PAGES = {"/printers/add.html", "/printers/*/edit.html"};

    @Bean
    public SecurityWebFilterChain farmSecurity(ServerHttpSecurity http,
                                               ReactiveUserDetailsService users,
                                               PasswordEncoder encoder) {
        return http
                .authenticationManager(authentication(users, encoder))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        // See the note on csrfCookieWebFilter() for why the handler is stated.
                        .csrfTokenRequestHandler(
                                new org.springframework.security.web.server.csrf
                                        .ServerCsrfTokenRequestAttributeHandler()))
                .authorizeExchange(ex -> ex
                        .pathMatchers(PUBLIC).permitAll()

                        // The actuator carries the beans, the build and the module structure.
                        .pathMatchers("/actuator/**").hasRole("ADMIN")

                        // The people roster. Administrators only, in every direction - who may
                        // heat a nozzle is exactly as sensitive as what may be heated, and reading
                        // the list of accounts is reconnaissance.
                        .pathMatchers("/api-users/**").hasRole("ADMIN")

                        // Changing what the farm is.
                        .pathMatchers(ADMIN_PAGES).hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api-manager/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/api-manager/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api-manager/**").hasRole("ADMIN")

                        // Running the machines - the M104 S300 boundary.
                        .pathMatchers(HttpMethod.POST, "/api-printer/**").hasAnyRole("OPERATOR", "ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api-jobs/**").hasAnyRole("OPERATOR", "ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api-jobs/**").hasAnyRole("OPERATOR", "ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api-files/**").hasAnyRole("OPERATOR", "ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api-files/**").hasAnyRole("OPERATOR", "ADMIN")

                        // Looking.
                        .pathMatchers(HttpMethod.GET, READ_PAGES).hasAnyRole("VIEWER", "OPERATOR", "ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api-farm/**", "/api-manager/**",
                                "/api-printer/**", "/api-jobs/**", "/api-files/**")
                        .hasAnyRole("VIEWER", "OPERATOR", "ADMIN")

                        // A path nobody has thought about is closed, not open.
                        .anyExchange().authenticated())
                .formLogin(form -> form.loginPage("/login"))
                .logout(logout -> logout.logoutUrl("/logout"))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint()))
                .build();
    }

    /**
     * A browser is sent to the login page; a fetch is told 401.
     *
     * <p>Without this the whole application uses one entry point, and form login's is a 302 to
     * {@code /login}. A {@code fetch} or an {@code EventSource} follows that redirect and receives
     * <strong>200 and a page of HTML</strong>, which the caller then tries to parse as JSON - so an
     * expired session shows up in the UI as a parse error rather than as "you are logged out", and
     * a test asserting "this endpoint is protected" passes against a 200.
     */
    private static org.springframework.security.web.server.ServerAuthenticationEntryPoint entryPoint() {
        var unauthorized = new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED);
        var toLogin = new org.springframework.security.web.server.authentication
                .RedirectServerAuthenticationEntryPoint("/login");
        return (exchange, denied) -> {
            var path = exchange.getRequest().getPath().value();
            var isMachineCaller = path.startsWith("/api-") || path.startsWith("/actuator");
            return isMachineCaller
                    ? unauthorized.commence(exchange, denied)
                    : toLogin.commence(exchange, denied);
        };
    }

    /**
     * The encoder is handed over explicitly, and leaving it out is a 500 on every sign-in.
     *
     * <p>{@code UserDetailsRepositoryReactiveAuthenticationManager} defaults to a
     * <em>delegating</em> encoder, which reads the {@code {bcrypt}} prefix off a stored hash to
     * decide how to check it. The hashes this farm stores have no prefix - they are what
     * {@code BCryptPasswordEncoder} produced - so the delegating encoder finds no id and throws,
     * and the login page answers 500 rather than "wrong password".
     *
     * <p>The bean comes from {@code identity}, which is the module that <em>writes</em> the
     * hashes. That is the point: one encoder, declared once, so the farm cannot end up writing
     * passwords with one scheme and checking them with another.
     */
    private static org.springframework.security.authentication.ReactiveAuthenticationManager
            authentication(ReactiveUserDetailsService users, PasswordEncoder encoder) {
        var manager = new UserDetailsRepositoryReactiveAuthenticationManager(users);
        manager.setPasswordEncoder(encoder);
        return manager;
    }

    @Bean
    public ReactiveUserDetailsService farmUserDetails(FarmUserDirectory users) {
        return new FarmUserDetailsService(users);
    }

    /**
     * Makes the CSRF token reach the response.
     *
     * <p>{@code CookieServerCsrfTokenRepository} writes the cookie lazily - only when something
     * subscribes to the token - so without this the browser never receives {@code XSRF-TOKEN} and
     * every POST from {@code client.js} is rejected with 403. It is the standard WebFlux
     * workaround and it is load-bearing, not boilerplate.
     */
    @Bean
    public WebFilter csrfCookieWebFilter() {
        return (exchange, chain) -> {
            Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
            return token == null ? chain.filter(exchange)
                    : token.doOnSuccess(t -> {}).then(chain.filter(exchange));
        };
    }
}
