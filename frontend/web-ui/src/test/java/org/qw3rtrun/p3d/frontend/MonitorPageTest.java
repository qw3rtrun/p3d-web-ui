package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the monitor page actually renders for the Storage tab - in the markup, not by eye.
 *
 * <p><strong>The claim worth testing is the negative one.</strong> A machine whose firmware reports
 * no card reader must get a dead tab <em>and no island</em>, and an island that mounted behind a
 * disabled tab would be invisible until it misbehaved: it would open an event stream to a machine
 * nobody can drive from there, and nothing on the page would show that it had. That is not
 * something a type checker or a unit test can see, because the decision is a {@code th:if} in a
 * template.
 *
 * <p>It renders the real page through the real filter chain, which is the lesson
 * {@code FarmSecurityTest.theProfilePageRendersWithAndWithoutAResult} records: asserting that a
 * controller returns a view name is not asserting that the view works. That test exists because a
 * template error sat behind three passing tests.
 *
 * <p>The machine's profile is the only thing that varies, so it is the only thing this stubs: a
 * {@link Primary} roster over {@link BootVuejsApplicationTests.Collaborators}' throwing one, whose
 * descriptor each test sets before it asks for the page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BootVuejsApplicationTests.Collaborators.class, MonitorPageTest.OneMachine.class})
class MonitorPageTest {

    private static final UUID MACHINE = UUID.randomUUID();

    /** The profile the page will be rendered against, set by each test. */
    private static final AtomicReference<MachineProfile> PROFILE =
            new AtomicReference<>(MachineProfile.unknown());

    /**
     * One machine, with whatever profile the running test asked for.
     *
     * <p>{@code Primary} because {@code Collaborators} contributes a roster that throws on every
     * call - which is right for a context-load test and useless for one that wants a page.
     *
     * <p>The method is deliberately <em>not</em> called {@code machines}: a bean of the same name
     * is an override, which Spring refuses outright before it ever gets as far as asking which one
     * is primary. Two names, and {@code Primary} then picks between them.
     */
    @TestConfiguration
    static class OneMachine {

        @Bean
        @Primary
        MachineManagementService rosterWithOneMachine() {
            return new MachineManagementService() {
                @Override
                public Descriptor fetch(UUID uuid) {
                    return descriptor();
                }

                @Override
                public Stream<Descriptor> fetchAll() {
                    return Stream.of(descriptor());
                }

                @Override
                public Descriptor add(String name, ConnectionDetails connection, MachineSpec spec) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Descriptor update(UUID uuid, String name, ConnectionDetails connection,
                                         MachineSpec spec) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void delete(UUID uuid) {
                    throw new UnsupportedOperationException();
                }

                private Descriptor descriptor() {
                    return new Descriptor(MACHINE, "alpha",
                            new ConnectionDetails("localhost", 8099),
                            MachineConnectionState.IDLE, PROFILE.get(), MachineSpec.undeclared());
                }
            };
        }
    }

    @Value("${local.server.port}")
    private int port;

    private WebTestClient web;

    @BeforeEach
    void bindToTheRealServer() {
        web = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(20)).build();
    }

    /** A machine whose firmware answered, with {@code Cap:SDCARD} set as given. */
    private static MachineProfile reporting(boolean sdCard) {
        return new MachineProfile(Map.of("FIRMWARE_NAME", "Marlin"),
                Map.of("SDCARD", sdCard), LocalDateTime.now());
    }

    private String monitorPage() {
        var body = signedIn().get().uri("/printers/" + MACHINE).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertNotNull(body, "the monitor page rendered nothing at all");
        return body;
    }

    // ---------------------------------------------------------------- the three states

    /** A machine with a card reader gets a live tab and the island behind it. */
    @Test
    void aMachineWithACardReaderGetsTheTabAndTheIsland() {
        PROFILE.set(reporting(true));

        var page = monitorPage();

        assertTrue(page.contains("href=\"#storage\""), "the tab should link to the pane");
        assertTrue(page.contains("id=\"storage\""), "the pane should be rendered");
        assertTrue(page.contains("data-p3d=\"storage-tab\""), "the island should be mounted");
        assertTrue(page.contains("data-uuid=\"" + MACHINE + "\""),
                "the island should be told which machine it is for");
    }

    /**
     * A machine whose firmware says it has no reader gets a dead tab - and <strong>no island</strong>.
     *
     * <p>The second half is the one that matters. A mounted island would open a per-printer event
     * stream, which instantiates a runtime server-side, for a tab the operator cannot even open.
     */
    @Test
    void aMachineWithNoCardReaderGetsADeadTabAndNoIsland() {
        PROFILE.set(reporting(false));

        var page = monitorPage();

        assertTrue(page.contains("nav-link disabled"), "the tab should be disabled");
        assertTrue(page.contains("This firmware reports no SD card support."),
                "the tooltip should say why");
        assertFalse(page.contains("data-p3d=\"storage-tab\""),
                "no island may be mounted behind a tab that cannot be opened");
        assertFalse(page.contains("href=\"#storage\""),
                "and nothing should link to a pane that is not there");
    }

    /**
     * A machine nothing has reached yet gets the same dead tab and the <em>other</em> sentence.
     *
     * <p>Two reasons, two messages: "this firmware has no reader" is a fact about the machine and
     * "nothing has asked it yet" is a fact about this farm, and an operator can act on the second.
     * Collapsing them would tell somebody whose printer is merely unplugged that their hardware
     * does not support SD cards.
     */
    @Test
    void aMachineNothingHasReachedSaysSoInsteadOfBlamingTheFirmware() {
        PROFILE.set(MachineProfile.unknown());

        var page = monitorPage();

        assertTrue(page.contains("nav-link disabled"), "the tab should be disabled");
        assertTrue(page.contains("This machine has not reported yet. Connect it and reload."),
                "the tooltip should say which of the two reasons it is");
        assertFalse(page.contains("This firmware reports no SD card support."),
                "an unreached machine must not be reported as unsupported hardware");
        assertFalse(page.contains("data-p3d=\"storage-tab\""));
    }

    /** The tooltip is markup Bootstrap only acts on if something constructs it - see `theme.ts`. */
    @Test
    void theDisabledTabCarriesTheMarkupTheTooltipNeeds() {
        PROFILE.set(reporting(false));

        var page = monitorPage();

        assertTrue(page.contains("data-bs-toggle=\"tooltip\""),
                "without this attribute startTheme() has nothing to attach a tooltip to");
    }

    // ---------------------------------------------------------------- the browser's flow

    /** The same cookie-carrying sign-in {@code FarmSecurityTest} uses, reduced to what is needed. */
    private WebTestClient signedIn() {
        var login = web.get().uri("/login").exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        var token = login.getResponseCookies().getFirst("XSRF-TOKEN");
        assertNotNull(token, "the login page must issue a CSRF cookie");

        var form = new LinkedMultiValueMap<String, String>();
        form.add("username", "operator");
        form.add("password", InMemoryFarmUsers.PASSWORD);
        form.add("_csrf", token.getValue());

        var result = web.post().uri("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .cookie("XSRF-TOKEN", token.getValue())
                .body(BodyInserters.fromFormData(form))
                .exchange()
                .returnResult(String.class);

        assertEquals(HttpStatus.FOUND, result.getStatus(), "sign-in should redirect");
        var session = result.getResponseCookies().getFirst("SESSION");
        assertNotNull(session, "a successful sign-in must establish a session");

        return web.mutate()
                .defaultCookie("XSRF-TOKEN", token.getValue())
                .defaultCookie("SESSION", session.getValue())
                .build();
    }
}
