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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the monitor page actually renders for its capability-gated tabs - in the markup, not by eye.
 *
 * <p>Two tabs, gated the same way on two different capabilities: Storage on {@code Cap:SDCARD},
 * Settings on {@code Cap:EEPROM}. The claim worth testing is the negative one: a machine reporting
 * no card reader must get a dead tab <em>and no island</em>, since a mounted island behind a
 * disabled tab would silently open an event stream to a machine nobody can drive from there - a
 * {@code th:if} decision no type checker or unit test can see.
 *
 * <p>Renders the real page through the real filter chain, as
 * {@code FarmSecurityTest.theProfilePageRendersWithAndWithoutAResult} argues: a controller
 * returning a view name is not the view working.
 *
 * <p>The machine's profile is the only thing that varies, so it is the only thing stubbed: a
 * {@link Primary} roster over {@link BootVuejsApplicationTests.Collaborators}' throwing one, whose
 * descriptor each test sets before asking for the page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BootVuejsApplicationTests.Collaborators.class, MonitorPageTest.OneMachine.class})
class MonitorPageTest {

    private static final UUID MACHINE = UUID.randomUUID();

    /** The profile the page will be rendered against, set by each test. */
    private static final AtomicReference<MachineProfile> PROFILE =
            new AtomicReference<>(MachineProfile.unknown());

    // One machine, with whatever profile the running test asked for. @Primary because
    // Collaborators' roster throws on every call. Deliberately not named `machines`: a bean of the
    // same name is an override, which Spring refuses outright before Primary gets a say.
    @TestConfiguration
    static class OneMachine {

        @Bean
        @Primary
        MachineManagementService rosterWithOneMachine() {
            return new MachineManagementService() {
                @Override
                public Optional<Descriptor> fetch(UUID uuid) {
                    return Optional.of(descriptor());
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

    /** A machine whose firmware answered, with {@code Cap:SDCARD} set as given and no EEPROM. */
    private static MachineProfile reporting(boolean sdCard) {
        return reporting(sdCard, false);
    }

    // The two capabilities are independent, and the tests below prove they are read independently:
    // a gate reading the wrong key would still pass every test that set both the same.
    private static MachineProfile reporting(boolean sdCard, boolean eeprom) {
        return new MachineProfile(Map.of("FIRMWARE_NAME", "Marlin"),
                Map.of("SDCARD", sdCard, "EEPROM", eeprom), LocalDateTime.now());
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

    // The "no island" half matters: a mounted island would open a per-printer event stream,
    // instantiating a runtime server-side, for a tab the operator cannot even open.
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

    // Two reasons, two messages: "this firmware has no reader" is a fact about the machine, and
    // "nothing has asked it yet" is a fact about this farm an operator can act on. Collapsing them
    // would tell somebody whose printer is merely unplugged that their hardware lacks SD support.
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

    // The tooltip is markup Bootstrap only acts on if something constructs it - see theme.ts.
    @Test
    void theDisabledTabCarriesTheMarkupTheTooltipNeeds() {
        PROFILE.set(reporting(false));

        var page = monitorPage();

        assertTrue(page.contains("data-bs-toggle=\"tooltip\""),
                "without this attribute startTheme() has nothing to attach a tooltip to");
    }

    // ---------------------------------------------------------------- the Settings tab

    // The capability's name on the wire is EEPROM. Issue #88 calls it EEPROM_SETTINGS, Marlin's
    // build macro that never leaves the compiler - this profile sets EEPROM, and the test below
    // proves the macro spelling is not what the page reads.
    @Test
    void aMachineWithAnEepromGetsTheSettingsTabAndTheIsland() {
        PROFILE.set(reporting(false, true));

        var page = monitorPage();

        assertTrue(page.contains("href=\"#settings\""), "the tab should link to the pane");
        assertTrue(page.contains("id=\"settings\""), "the pane should be rendered");
        assertTrue(page.contains("data-p3d=\"settings-tab\""), "the island should be mounted");
    }

    // Same claim as the Storage tab's negative case: a mounted island would open a per-printer
    // event stream behind a tab nobody can open, and then offer M500 to a machine with nothing to
    // store.
    @Test
    void aMachineWithNoEepromGetsADeadSettingsTabAndNoIsland() {
        PROFILE.set(reporting(false, false));

        var page = monitorPage();

        assertTrue(page.contains("This firmware reports no EEPROM, so it has no settings to store."),
                "the tooltip should say why");
        assertFalse(page.contains("data-p3d=\"settings-tab\""),
                "no island may be mounted behind a tab that cannot be opened");
        assertFalse(page.contains("href=\"#settings\""),
                "and nothing should link to a pane that is not there");
        assertTrue(page.contains(">Settings</a>"),
                "the tab is still named, so the absence is explained rather than hidden");
    }

    // Marlin's M115 writes Cap:EEPROM:0|1 under ENABLED(EEPROM_SETTINGS) - the macro is the
    // #define in Configuration.h, the capability is the name on the wire. Issue #88's checklist
    // says the macro; this catches a gate written from the issue instead of the measurement.
    @Test
    void theBuildMacroSpellingIsNotTheCapabilityThePageReads() {
        PROFILE.set(new MachineProfile(Map.of("FIRMWARE_NAME", "Marlin"),
                Map.of("EEPROM_SETTINGS", true), LocalDateTime.now()));

        var page = monitorPage();

        assertFalse(page.contains("data-p3d=\"settings-tab\""),
                "Cap:EEPROM_SETTINGS is not a line any firmware writes");
        assertTrue(page.contains("This firmware reports no EEPROM, so it has no settings to store."));
    }

    /** An unreached machine gets the other sentence here too, for the Storage tab's reason. */
    @Test
    void anUnreachedMachineIsNotReportedAsHavingNoEeprom() {
        PROFILE.set(MachineProfile.unknown());

        var page = monitorPage();

        assertTrue(page.contains("This machine has not reported yet. Connect it and reload."));
        assertFalse(page.contains("This firmware reports no EEPROM, so it has no settings to store."),
                "an unreached machine must not be reported as unsupported hardware");
        assertFalse(page.contains("data-p3d=\"settings-tab\""));
    }

    // A test setting both capabilities alike would not catch the gates reading the same key (both
    // hasSdCard(), say) - this checks a card reader with no EEPROM gets a live Storage tab and a
    // dead Settings one, and the reverse.
    @Test
    void aCardReaderDoesNotImplyAnEepromOrTheOtherWayRound() {
        PROFILE.set(reporting(true, false));
        var cardOnly = monitorPage();

        assertTrue(cardOnly.contains("data-p3d=\"storage-tab\""));
        assertFalse(cardOnly.contains("data-p3d=\"settings-tab\""));

        PROFILE.set(reporting(false, true));
        var eepromOnly = monitorPage();

        assertFalse(eepromOnly.contains("data-p3d=\"storage-tab\""));
        assertTrue(eepromOnly.contains("data-p3d=\"settings-tab\""));
    }

    // Issue #88 item 1: the store button moved to the Settings tab rather than being duplicated.
    // The monitor island is Vue, so its markup lives in the bundle, not this page - an incomplete
    // move would show up as two ways to do one irreversible thing.
    @Test
    void theMonitorTabNoLongerOffersToStoreTheSettings() {
        PROFILE.set(reporting(false, true));

        var page = monitorPage();

        assertFalse(page.contains("Store settings (M500)"),
                "the Monitor tab's store button moved rather than being duplicated");
    }

    // ---------------------------------------------------------------- the browser's flow

    // The same cookie-carrying sign-in FarmSecurityTest uses, reduced to what is needed.
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
