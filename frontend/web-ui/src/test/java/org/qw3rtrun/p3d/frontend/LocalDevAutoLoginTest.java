package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "local" profile's auto-login - issue #90.
 *
 * <p>Bound to a real port and with no cookie at all on the request, the same distinction
 * {@link FarmSecurityTest} draws between "the request was refused" and "the request reached
 * application code" - a 200/500 against a bare 401 - is what proves the admin principal was there
 * before {@code authorizeExchange} looked for one, not merely that the endpoint allows anonymous
 * access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import(BootVuejsApplicationTests.Collaborators.class)
class LocalDevAutoLoginTest {

    @Value("${local.server.port}")
    private int port;

    private WebTestClient web() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** An ADMIN-only endpoint, with no session and no CSRF token, answers rather than refusing. */
    @Test
    void anAnonymousCallerReachesAnAdminOnlyEndpoint() {
        web().get().uri("/api-users")
                .exchange()
                .expectStatus().isOk();
    }

    /** The principal the filter wrote is "admin", holding ADMIN - not merely "some authenticated user". */
    @Test
    void theAnonymousCallerIsAttributedToAdmin() {
        var body = web().get().uri("/profile")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();

        assertNotNull(body);
        assertTrue(body.contains("admin"), "the profile page should name the auto-logged-in user: " + body);
        assertTrue(body.contains("ADMIN"), "the profile page should show the ADMIN role: " + body);
    }

    /** The actuator is ADMIN-only in every profile; the auto-login reaches that bar too. */
    @Test
    void anAnonymousCallerReadsTheActuator() {
        web().get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    // A 500 here would mean the request got through unauthenticated into a stub that throws -
    // Collaborators' stubs all do that - so isOk() above is the stronger claim: these endpoints are
    // real enough to answer, which only an authenticated ADMIN reaches without a 401/403 first.
    @Test
    void sanityCheckStatusIsNeverUnauthorizedOrForbidden() {
        var status = web().get().uri("/api-users").exchange().returnResult(String.class).getStatus();
        assertTrue(status != HttpStatus.UNAUTHORIZED && status != HttpStatus.FORBIDDEN,
                "an anonymous caller under the local profile must never be refused: " + status);
    }
}
