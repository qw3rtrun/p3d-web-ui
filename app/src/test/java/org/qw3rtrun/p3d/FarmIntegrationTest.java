package org.qw3rtrun.p3d;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What every per-feature integration test in this module is built on - issue #43.
 *
 * <p>The three rules the issue sets, and how this holds them:
 *
 * <ul>
 *   <li><strong>"Tests call endpoints and validate results."</strong> Everything here goes over
 *       HTTP against a real port. There is no autowired service, no repository and no application
 *       event published by hand. A subclass that needs an {@code @Autowired} field has found a gap
 *       in the HTTP surface, and that is a finding rather than a licence.</li>
 *   <li><strong>"Simulator is out of scope - it mocked."</strong> The far end is {@link MockPrinter},
 *       which binds a loopback port and speaks Marlin. The machine is registered at that port
 *       through {@code POST /api-manager}, so nothing about the application is reconfigured for a
 *       test - the farm simply has a printer there.</li>
 *   <li><strong>"Split by Feature."</strong> One subclass per feature, named for it.</li>
 * </ul>
 *
 * <h2>The two observables</h2>
 *
 * A test here may assert on exactly two things, and both are at the system's boundary:
 *
 * <ol>
 *   <li>what came back over HTTP or SSE - {@link #farm} and {@link Events};</li>
 *   <li>what reached the machine - {@link MockPrinter#commands()}.</li>
 * </ol>
 *
 * The second is not an in-the-middle probe. It is the far boundary, and it is the only assertion
 * that has ever caught this repository's recurring failure: three features - the motion commands,
 * the Storage tab's commands, then its replies - shipped complete, green and silent, each answering
 * 200 while putting nothing on the wire. A test that stops at the HTTP response would have passed
 * on all three.
 *
 * <h2>Signing in</h2>
 *
 * As a browser does: fetch {@code /login} for the {@code XSRF-TOKEN} cookie, post the form, keep the
 * {@code SESSION} cookie. A mock exchange would skip the CSRF hand-off, which is issued by one
 * filter and checked by another.
 *
 * <p><strong>Everything runs as the administrator</strong>, who may both change what the farm is and
 * drive a machine. Which role may do what is a different question with its own test -
 * {@code FarmSecurityTest} in {@code :frontend:web-ui} drives every role against every surface - and
 * re-deciding it here would be a second copy of that matrix to keep in step.
 *
 * <h2>Why one context</h2>
 *
 * Every subclass carries the same annotations, so Spring caches one application context across all
 * of them and the suite pays for one boot. The price is that state leaks between classes, which is
 * what {@link #tidyUp()} is for: every machine a test registers is deleted, which publishes
 * {@code MDeletedEvent} and so releases its runtime, its socket and its event sink.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class FarmIntegrationTest {

    /** Long enough for a dial, a handshake and a round trip on a loaded build agent. */
    protected static final Duration PATIENCE = Duration.ofSeconds(15);

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @Value("${p3d.security.initial-admin.username}")
    private String admin;

    @Value("${p3d.security.initial-admin.password}")
    private String adminPassword;

    /**
     * One sign-in for the whole suite.
     *
     * <p>Signing in per test cost about a second each, nearly all of it BCrypt - which is
     * deliberately slow and is the one thing here that is <em>meant</em> to be. Every subclass
     * shares one application context, so the session a browser would keep across a sitting is the
     * honest thing to share too; the port is the key, so a context that is ever rebuilt gets a
     * fresh one rather than a stale cookie against a new server.
     *
     * <p>What this gives up is per-test proof that sign-in works. That is not a loss: it is
     * {@code FarmSecurityTest}'s subject in {@code :frontend:web-ui}, which performs the whole
     * browser flow for every role.
     */
    private record Session(int port, String csrf, String cookie) {
    }

    private static volatile Session cached;

    /** Signed in, carrying both cookies, with the CSRF header set on every write. */
    protected WebTestClient farm;

    private final List<UUID> registered = new ArrayList<>();
    private final List<MockPrinter> machines = new ArrayList<>();
    private final List<Events> streams = new ArrayList<>();

    @BeforeEach
    void signIn() {
        var anonymous = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();

        var session = cached;
        if (session == null || session.port() != port) {
            session = establish(anonymous);
            cached = session;
        }

        farm = anonymous.mutate()
                .defaultCookie("XSRF-TOKEN", session.csrf())
                .defaultCookie("SESSION", session.cookie())
                .defaultHeader("X-XSRF-TOKEN", session.csrf())
                .build();
    }

    /** The browser's own flow: fetch the token, post the form, keep the session. */
    private Session establish(WebTestClient anonymous) {
        var login = anonymous.get().uri("/login").exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        var token = login.getResponseCookies().getFirst("XSRF-TOKEN");
        assertNotNull(token, "the login page must issue a CSRF cookie or no form can be posted");
        var csrf = token.getValue();

        var form = new LinkedMultiValueMap<String, String>();
        form.add("username", admin);
        form.add("password", adminPassword);
        form.add("_csrf", csrf);

        var result = anonymous.post().uri("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .cookie("XSRF-TOKEN", csrf)
                .body(BodyInserters.fromFormData(form))
                .exchange()
                .returnResult(String.class);

        assertEquals(302, result.getStatus().value(),
                "sign-in should redirect; the farm's own administrator could not log in");
        var cookie = result.getResponseCookies().getFirst("SESSION");
        assertNotNull(cookie, "a successful sign-in must establish a session");
        return new Session(port, csrf, cookie.getValue());
    }

    /**
     * Gives back everything the test took: the streams, the machines, the sockets.
     *
     * <p>Deleting the printer is what releases its runtime - {@code MDeletedEvent} reaches
     * {@code PrinterReactorManager.release}, which stops the reactor, disposes any retry still in
     * flight, closes the terminal and completes the update sink. Closing the mock without that would
     * leave a reactor redialling a dead port for the rest of the suite.
     */
    @AfterEach
    void tidyUp() {
        streams.forEach(Events::close);
        streams.clear();
        for (var id : registered) {
            try {
                farm.delete().uri("/api-manager/{id}", id).exchange();
            } catch (RuntimeException e) {
                // The farm is going away anyway; a failed cleanup must not mask the real failure.
            }
        }
        registered.clear();
        machines.forEach(MockPrinter::close);
        machines.clear();
    }

    // ------------------------------------------------------------------ the farm

    /** A mocked machine, listening, and closed when the test ends. */
    protected MockPrinter machine(MockPrinter.Profile profile) {
        var printer = MockPrinter.listening(profile);
        machines.add(printer);
        return printer;
    }

    /** Registers a machine pointed at the mock, through the endpoint an administrator uses. */
    protected UUID register(String name, MockPrinter printer) {
        return register(name, "localhost", printer.port(), null);
    }

    /** The same, declaring a spec - the half of a machine a person types rather than discovers. */
    protected UUID register(String name, MockPrinter printer, String specJson) {
        return register(name, "localhost", printer.port(), specJson);
    }

    /**
     * Registers a machine at an arbitrary address - used for the one that cannot be reached.
     *
     * <p>The body is written out as JSON rather than built from
     * {@code FDMPrinterManagementController.PrinterPayload}. {@code :app} can see that record, but
     * binding it here would make the test agree with the server by construction; what is under test
     * is the shape a browser sends.
     */
    protected UUID register(String name, String host, int port, String specJson) {
        var body = "{\"name\":\"" + name + "\",\"host\":\"" + host + "\",\"port\":" + port
                + (specJson == null ? "" : ",\"spec\":" + specJson) + "}";

        var machine = json(farm.post().uri("/api-manager")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk());

        var id = UUID.fromString((String) machine.get("uuid"));
        registered.add(id);
        return id;
    }

    /** What the roster says about a machine - {@code Descriptor}, as the browser receives it. */
    protected Map<String, Object> describe(UUID id) {
        return json(farm.get().uri("/api-manager/{id}", id).exchange().expectStatus().isOk());
    }

    /** Asks the farm to dial the machine. Returns when the request is accepted, not when it is up. */
    protected void connect(UUID id) {
        farm.post().uri("/api-printer/{id}/connect", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"connect\":true}")
                .exchange()
                .expectStatus().isOk();
    }

    protected void disconnect(UUID id) {
        farm.post().uri("/api-printer/{id}/connect", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"connect\":false}")
                .exchange()
                .expectStatus().isOk();
    }

    /** Where the machine's connection is, asked the way a page about to render one asks. */
    protected String connectionState(UUID id) {
        var status = json(farm.get().uri("/api-printer/{id}/connection", id)
                .exchange().expectStatus().isOk());
        return (String) status.get("state");
    }

    /**
     * Dials the machine and waits until the firmware has acknowledged a block this session sent.
     *
     * <p>{@code IDLE} and not {@code ONLINE}: a socket being open proves the machine is reachable,
     * not that it is usable. A test that went on to send a command after {@code ONLINE} would be
     * racing the handshake.
     */
    protected void connectAndWait(UUID id) {
        connect(id);
        await("machine " + id + " to reach IDLE", () -> "IDLE".equals(connectionState(id)));
    }

    /** Posts a command with no body - the endpoints that build their own payload server-side. */
    protected WebTestClient.ResponseSpec command(UUID id, String path) {
        return farm.post().uri("/api-printer/{id}" + path, id).exchange();
    }

    /** Posts a command with a JSON body. */
    protected WebTestClient.ResponseSpec command(UUID id, String path, String body) {
        return farm.post().uri("/api-printer/{id}" + path, id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    // ------------------------------------------------------------------ the event stream

    /** Opens this machine's SSE channel and starts recording what a browser would receive. */
    protected Events watch(UUID id) {
        var events = new Events("/api-printer/" + id + "/stream");
        streams.add(events);
        return events;
    }

    /** Opens the farm-wide channel - the one a dashboard holds instead of one per machine. */
    protected Events watchTheFarm() {
        var events = new Events("/api-farm/stream");
        streams.add(events);
        return events;
    }

    /**
     * An open SSE subscription, recording what arrived.
     *
     * <p>Each element is one {@code EventMessage} as JSON: {@code id}, {@code type} and the event
     * itself. {@code type} is the event's <em>simple class name</em>, which makes it a
     * browser-visible name that a rename in {@code :gcode} would change - so a test naming one here
     * is pinning the same contract {@code EventStreamContractTest} pins, at the other end of the
     * wire.
     *
     * <p>Decoded into a map rather than bound to {@code EventMessage}, deliberately: the browser
     * has no such class, and binding to it would let a field that never reaches the wire pass
     * unnoticed.
     */
    protected final class Events implements AutoCloseable {

        private final List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
        private final Disposable subscription;

        @SuppressWarnings("unchecked")
        private Events(String uri) {
            var body = farm.get().uri(uri)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus().isOk()
                    .returnResult(String.class)
                    .getResponseBody();
            this.subscription = body
                    .map(frame -> JSON.readValue(frame, Map.class))
                    .subscribe(frame -> seen.add((Map<String, Object>) frame), failure -> {
                        // The stream ends when the machine is released. Not a failure of the test.
                    });
        }

        /** Every message received so far, oldest first. */
        public List<Map<String, Object>> all() {
            return List.copyOf(seen);
        }

        /** The {@code type} of every message, which is what a browser switches on. */
        public List<String> types() {
            return seen.stream().map(m -> (String) m.get("type")).toList();
        }

        public boolean saw(String type) {
            return types().contains(type);
        }

        /** Waits for a message of this type and hands back its {@code event} payload. */
        public Map<String, Object> await(String type) {
            FarmIntegrationTest.await("an SSE message of type " + type
                    + " (saw " + types() + ")", () -> saw(type));
            return payloadOf(type);
        }

        /** The payload of the most recent message of this type, or null. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> payloadOf(String type) {
            Map<String, Object> last = null;
            for (var message : seen) {
                if (type.equals(message.get("type"))) {
                    last = (Map<String, Object>) message.get("event");
                }
            }
            return last;
        }

        @Override
        public void close() {
            subscription.dispose();
        }
    }

    // ------------------------------------------------------------------ waiting, and reading JSON

    /** Waits for something to become true, and says what it was waiting for when it does not. */
    protected static void await(String what, BooleanSupplier condition) {
        await(what, PATIENCE, condition);
    }

    protected static void await(String what, Duration limit, BooleanSupplier condition) {
        var deadline = System.nanoTime() + limit.toNanos();
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (System.nanoTime() < deadline);
        fail("timed out after " + limit + " waiting for " + what);
    }

    /**
     * A JSON response body as a map.
     *
     * <p>Not bound to the record the controller returns. {@code Descriptor} and
     * {@code ConnectionStatus} live in {@code api}, and deserialising into them would assert that
     * the server agrees with itself; a map asserts on the field names a browser actually reads.
     */
    protected static Map<String, Object> json(WebTestClient.ResponseSpec response) {
        var body = response.expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
        }).returnResult().getResponseBody();
        assertNotNull(body, "expected a JSON object in the response body");
        return body;
    }
}
