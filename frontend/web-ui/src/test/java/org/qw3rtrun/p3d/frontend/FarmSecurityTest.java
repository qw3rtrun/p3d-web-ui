package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.identity.FarmRole;
import org.qw3rtrun.p3d.api.identity.FarmUserDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may do what to a farm of heaters - plan item D3.
 *
 * <p>The plan's reason for this item is a safety one rather than a data one: <em>"a printer is a
 * heater, and an unauthenticated {@code M104 S300} is a safety problem"</em>. So what is asserted
 * here is not that a login page exists, but that a request without the right person behind it
 * <strong>never reaches application code</strong>.
 *
 * <p><strong>That is what makes 500 the useful signal.</strong> The collaborators in
 * {@link BootVuejsApplicationTests.Collaborators} throw on every call, so a 500 means the request
 * got through the filter chain and a 401 or 403 means it did not. A test that only checked for
 * "not 200" would pass against an application that was merely broken.
 *
 * <p>It binds to a real port rather than a mock exchange, so the sign-in it performs is the one a
 * browser performs - including the CSRF token, which is issued by one filter and checked by
 * another and is therefore exactly the kind of thing a mock setup quietly skips.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BootVuejsApplicationTests.Collaborators.class)
class FarmSecurityTest {

    @Value("${local.server.port}")
    private int port;

    /** The same in-memory roster the application is using, so a test can seed a throwaway user. */
    @Autowired
    private FarmUserDirectory users;

    private WebTestClient web;

    private static final UUID MACHINE = UUID.randomUUID();

    @BeforeEach
    void bindToTheRealServer() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(20)).build();
    }

    // ---------------------------------------------------------------- anonymous

    /** Watching the farm says what is printing and where, so it is not public either. */
    @Test
    void anAnonymousCallerCannotWatchTheFarm() {
        web.get().uri("/api-farm/stream").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anAnonymousCallerCannotReadTheRoster() {
        web.get().uri("/api-manager").exchange().expectStatus().isUnauthorized();
    }

    /** The actuator carries the beans, the build and the module structure. */
    @Test
    void anAnonymousCallerCannotReadTheActuator() {
        web.get().uri("/actuator/health").exchange().expectStatus().isUnauthorized();
    }

    /**
     * An anonymous write is stopped by CSRF before authentication is even consulted - 403, not 401.
     *
     * <p>Asserted as 403 deliberately rather than loosened to "some error". The two filters guard
     * different things and run in that order, and a test that accepted either would go on passing
     * if authentication were removed altogether. The next test is the other half: it supplies a
     * valid token, and the request is then refused for the other reason.
     */
    @Test
    void anAnonymousWriteIsRefusedForWantOfACsrfToken() {
        web.post().uri("/api-printer/{id}/connect", MACHINE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"connect\":true}")
                .exchange()
                .expectStatus().isForbidden();
    }

    /** With the token, the same anonymous write is refused for want of a user. */
    @Test
    void aWriteWithAValidCsrfTokenStillNeedsAUser() {
        var session = new Session();
        session.fetchCsrfToken();

        session.post("/api-printer/" + MACHINE + "/connect", "{\"connect\":true}")
                .expectStatus().isUnauthorized();
    }

    // ---------------------------------------------------------------- by role

    /** A viewer may look at the farm. 500 is the stub throwing - proof the request got through. */
    @Test
    void aViewerMayReadTheRoster() {
        var session = new Session().signIn("viewer");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                session.get("/api-manager").returnResult(String.class).getStatus(),
                "a viewer should have been let through to the roster");
    }

    /** A viewer may not heat a nozzle - the boundary this whole item exists to draw. */
    @Test
    void aViewerMayNotCommandAPrinter() {
        var session = new Session().signIn("viewer");

        session.post("/api-printer/" + MACHINE + "/set-temp", "{\"temperature\":300}")
                .expectStatus().isForbidden();
    }

    /** An operator may. */
    @Test
    void anOperatorMayCommandAPrinter() {
        var session = new Session().signIn("operator");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                session.post("/api-printer/" + MACHINE + "/set-temp", "{\"temperature\":210}")
                        .returnResult(String.class).getStatus(),
                "an operator should have been let through to the printer");
    }

    /** An operator may not change what the farm is. */
    @Test
    void anOperatorMayNotRegisterAMachine() {
        var session = new Session().signIn("operator");

        session.post("/api-manager", "{\"name\":\"new\",\"host\":\"localhost\",\"port\":8099}")
                .expectStatus().isForbidden();
    }

    /** An administrator may. */
    @Test
    void anAdminMayRegisterAMachine() {
        var session = new Session().signIn("admin");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                session.post("/api-manager", "{\"name\":\"new\",\"host\":\"localhost\",\"port\":8099}")
                        .returnResult(String.class).getStatus(),
                "an admin should have been let through to the roster");
    }

    /** And only an administrator reads the actuator. */
    @Test
    void anOperatorMayNotReadTheActuator() {
        new Session().signIn("operator")
                .get("/actuator/health").expectStatus().isForbidden();
    }

    @Test
    void anAdminMayReadTheActuator() {
        new Session().signIn("admin")
                .get("/actuator/health").expectStatus().isOk();
    }

    /** Only an administrator may see who has an account. */
    @Test
    void anOperatorMayNotListTheUsers() {
        new Session().signIn("operator").get("/api-users").expectStatus().isForbidden();
    }

    /**
     * An administrator may, and what comes back carries no password hashes.
     *
     * <p>Asserted on the response body rather than on the record's shape, because the failure this
     * guards against is somebody returning {@code FarmUser} directly from the controller - which
     * would compile, look right, and publish every hash in the farm to anyone who can reach the
     * endpoint.
     */
    @Test
    void anAdminMayListTheUsersAndSeesNoHashes() {
        var body = new Session().signIn("admin").get("/api-users")
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertNotNull(body);
        assertTrue(body.contains("operator"), body);
        assertFalse(body.contains("$2a$"),
                "a BCrypt hash must never leave the server: " + body);
        assertFalse(body.toLowerCase().contains("passwordhash"),
                "nor a field that would carry one: " + body);
    }

    /** A wrong password signs nobody in. */
    @Test
    void aWrongPasswordDoesNotSignAnybodyIn() {
        var session = new Session();
        session.fetchCsrfToken();

        var result = session.form("/login", "username", "admin", "password", "not it")
                .returnResult(String.class);

        assertEquals(HttpStatus.FOUND, result.getStatus());
        assertEquals("/login?error", result.getResponseHeaders().getLocation().toString(),
                "a failed sign-in should land back on the login page");
    }

    // ---------------------------------------------------------------- your own account

    /**
     * Anybody signed in may change their own password, including a viewer.
     *
     * <p>This is the one hole in "{@code /api-users} is ADMIN-only", and it has to exist: without
     * it the only account that could ever change a password is the one everybody would then share.
     */
    @Test
    void aViewerMayChangeTheirOwnPassword() {
        var username = "temp-" + UUID.randomUUID();
        users.add(username, "the old one", FarmRole.VIEWER);

        var session = new Session().signIn(username, "the old one");
        var result = session.form("/profile/password",
                "currentPassword", "the old one",
                "newPassword", "the new one",
                "confirmation", "the new one").returnResult(String.class);

        // 303 and not 302: a Thymeleaf `redirect:` after a POST is post-redirect-get, so a
        // refresh of the result page does not re-submit the form. Spring Security's own login
        // handler answers 302, which is why the two differ.
        assertEquals(HttpStatus.SEE_OTHER, result.getStatus());
        assertEquals("/profile?changed", result.getResponseHeaders().getLocation().toString());

        var stored = users.byName(username).orElseThrow().passwordHash();
        assertTrue(new BCryptPasswordEncoder().matches("the new one", stored),
                "the new password should work");
    }

    /**
     * The current password is checked, and that is what stops a borrowed session being permanent.
     *
     * <p>Without it, anybody holding a session - one left open on a workshop machine, a stolen
     * cookie - sets a new password and the owner is locked out of their own account.
     */
    @Test
    void aWrongCurrentPasswordChangesNothing() {
        var username = "temp-" + UUID.randomUUID();
        users.add(username, "the old one", FarmRole.OPERATOR);
        var before = users.byName(username).orElseThrow().passwordHash();

        var session = new Session().signIn(username, "the old one");
        var result = session.form("/profile/password",
                "currentPassword", "not it",
                "newPassword", "the new one",
                "confirmation", "the new one").returnResult(String.class);

        assertEquals("/profile?error=wrong", result.getResponseHeaders().getLocation().toString());
        assertEquals(before, users.byName(username).orElseThrow().passwordHash(),
                "the stored password must be untouched");
    }

    /** A mismatched confirmation is refused before anything is written. */
    @Test
    void aMismatchedConfirmationChangesNothing() {
        var username = "temp-" + UUID.randomUUID();
        users.add(username, "the old one", FarmRole.VIEWER);
        var before = users.byName(username).orElseThrow().passwordHash();

        var result = new Session().signIn(username, "the old one")
                .form("/profile/password",
                        "currentPassword", "the old one",
                        "newPassword", "one thing",
                        "confirmation", "another thing").returnResult(String.class);

        assertEquals("/profile?error=mismatch",
                result.getResponseHeaders().getLocation().toString());
        assertEquals(before, users.byName(username).orElseThrow().passwordHash());
    }

    /**
     * The page renders - for a plain visit and for every result it can be redirected back with.
     *
     * <p><strong>This is a regression test for a 500 the other profile tests could not catch.</strong>
     * They drove the POST and the redirect and never rendered the page, so a template error sat
     * behind three passing tests: `${param.error}` is an <em>empty list</em> when the parameter is
     * absent rather than null, Thymeleaf reads that as true, and `param.error[0]` then threw.
     * Asserting a redirect is not asserting that the thing it redirects to works.
     */
    @Test
    void theProfilePageRendersWithAndWithoutAResult() {
        var session = new Session().signIn("operator");

        for (var uri : List.of(
                "/profile", "/profile?changed",
                "/profile?error=wrong", "/profile?error=mismatch", "/profile?error=empty")) {
            var body = session.get(uri)
                    .expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();

            assertNotNull(body, uri);
            assertTrue(body.contains("operator"), uri + " should name the signed-in user");
            assertTrue(body.contains("OPERATOR"), uri + " should show their role");
        }
    }

    /** And the page itself needs a session, like every other page. */
    @Test
    void anAnonymousCallerIsSentToSignInForTheProfile() {
        var result = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .build()
                .get().uri("/profile").exchange()
                .expectStatus().isFound()
                .returnResult(String.class);

        assertTrue(result.getResponseHeaders().getLocation().toString().contains("/login"),
                "a browser should be redirected, not refused");
    }

    // ---------------------------------------------------------------- the browser's flow

    /**
     * One browser's cookies - the CSRF token and the session - carried the way a browser carries them.
     *
     * <p>Written out rather than mocked because the CSRF token is issued by one filter and verified
     * by another, and the hand-off between them is the part most likely to be wrong: it is the
     * reason {@code SecurityConfiguration} carries a {@code WebFilter} whose only job is to make
     * the cookie actually get written.
     */
    private final class Session {

        private String csrf;
        private String sessionCookie;

        /** A GET of the login page is what makes the server issue an XSRF-TOKEN cookie. */
        Session fetchCsrfToken() {
            var result = web.get().uri("/login").exchange()
                    .expectStatus().isOk()
                    .returnResult(String.class);
            var token = result.getResponseCookies().getFirst("XSRF-TOKEN");
            assertNotNull(token, "the login page must issue a CSRF cookie or no form can be posted");
            csrf = token.getValue();
            var session = result.getResponseCookies().getFirst("SESSION");
            if (session != null) {
                sessionCookie = session.getValue();
            }
            return this;
        }

        Session signIn(String username) {
            return signIn(username, InMemoryFarmUsers.PASSWORD);
        }

        Session signIn(String username, String password) {
            fetchCsrfToken();
            var result = form("/login",
                    "username", username,
                    "password", password).returnResult(String.class);

            assertEquals(HttpStatus.FOUND, result.getStatus(), "sign-in should redirect");
            assertEquals("/", result.getResponseHeaders().getLocation().toString(),
                    "a successful sign-in should land on the farm");
            var session = result.getResponseCookies().getFirst("SESSION");
            assertNotNull(session, "a successful sign-in must establish a session");
            sessionCookie = session.getValue();
            return this;
        }

        WebTestClient.ResponseSpec form(String uri, String... pairs) {
            var body = new LinkedMultiValueMap<String, String>();
            for (int i = 0; i < pairs.length; i += 2) {
                body.add(pairs[i], pairs[i + 1]);
            }
            body.add("_csrf", csrf);
            return cookies(web.post().uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(body))).exchange();
        }

        WebTestClient.ResponseSpec get(String uri) {
            return cookies(web.get().uri(uri)).exchange();
        }

        WebTestClient.ResponseSpec post(String uri, String json) {
            return cookies(web.post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-XSRF-TOKEN", csrf)
                    .bodyValue(json)).exchange();
        }

        private WebTestClient.RequestHeadersSpec<?> cookies(
                WebTestClient.RequestHeadersSpec<?> spec) {
            if (csrf != null) {
                spec = spec.cookie("XSRF-TOKEN", csrf);
            }
            if (sessionCookie != null) {
                spec = spec.cookie("SESSION", sessionCookie);
            }
            return spec;
        }
    }
}
