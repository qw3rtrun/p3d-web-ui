package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The counterpart to {@link LocalDevAutoLoginTest}: with no {@code local} profile active - the
 * default this farm boots with everywhere but a developer's own machine - the auto-login bean must
 * not exist at all, and the farm's ordinary rule applies unchanged: an anonymous caller is refused.
 *
 * <p>This is what makes issue #90's change strictly additive rather than a property somebody could
 * leave on: the bean is gated by {@code @Profile("local")}, not by a flag that defaults to off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BootVuejsApplicationTests.Collaborators.class)
class LocalDevAutoLoginDisabledByDefaultTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    @Test
    void theAutoLoginBeanDoesNotExistOutsideTheLocalProfile() {
        assertThrows(NoSuchBeanDefinitionException.class,
                () -> context.getBean("localAdminAutoLogin"));
    }

    @Test
    void anAnonymousCallerIsStillRefused() {
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(20))
                .build()
                .get().uri("/api-users")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
