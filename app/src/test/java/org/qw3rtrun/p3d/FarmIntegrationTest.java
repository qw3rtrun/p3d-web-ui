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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What every per-feature integration test in this module is built on - issue #43: one subclass per
 * feature, everything over HTTP against a real port, the far end a {@link MockPrinter} registered
 * through {@code POST /api-manager}. No autowired service, no repository, no hand-published event -
 * a subclass needing an {@code @Autowired} field has found a gap in the HTTP surface, which is a
 * finding rather than a licence.
 *
 * <h2>The two observables</h2>
 * Only two assertions are allowed, both at a boundary: what came back over HTTP or SSE
 * ({@link #farm}, {@link Events}), and what reached the machine ({@link MockPrinter#commands()}).
 * The second is the far boundary, not an in-the-middle probe, and it is the only assertion that has
 * caught this repository's recurring failure - the motion commands, the Storage tab's commands, then
 * its replies all shipped green and silent, answering 200 with nothing on the wire.
 *
 * <h2>Signing in, and as whom</h2>
 * As a browser does: fetch {@code /login} for the {@code XSRF-TOKEN} cookie, post the form, keep the
 * {@code SESSION} cookie - a mock exchange would skip a CSRF hand-off issued by one filter and
 * checked by another. Everything runs as the administrator; which role may do what is
 * {@code FarmSecurityTest}'s matrix in {@code :frontend:web-ui}, and a second copy here would have
 * to be kept in step.
 *
 * <h2>Why one context</h2>
 * Identical annotations on every subclass let Spring cache one context, so the suite boots once. The
 * price is leaked state, which {@link #tidyUp()} pays: every registered machine is deleted, which
 * publishes {@code MDeletedEvent} and releases its runtime, socket and event sink.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class FarmIntegrationTest {

    /**
     * Thirty seconds - long enough for a dial, a handshake and a round trip on a loaded build agent.
     * Before issue #54 this was a real bottleneck: every {@code PrinterReactor} used
     * {@code Schedulers.single()}, Reactor's JVM-wide cached thread, and ran a blocking JPA write on
     * it seven times per connect (one firmware report, five capabilities, each re-publishing
     * {@code MRegisteredEvent}). A machine now has its own virtual threads,
     * {@code p3d-printer-<id>} and {@code p3d-listener-<id>}, but the queueing only moved - a dozen
     * machines against H2 on a two-core runner still wait on a database. It costs nothing when a
     * test passes and only lengthens a failure.
     */
    protected static final Duration PATIENCE = Duration.ofSeconds(30);

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @Value("${p3d.security.initial-admin.username}")
    private String admin;

    @Value("${p3d.security.initial-admin.password}")
    private String adminPassword;

    /**
     * One sign-in for the whole suite - per test it cost about a second each, nearly all BCrypt,
     * which is deliberately slow. Keyed by port, so a rebuilt context gets a fresh session rather
     * than a stale cookie. Per-test proof that sign-in works is {@code FarmSecurityTest}'s subject.
     */
    private record Session(int port, String csrf, String cookie) {
    }

    private static volatile Session cached;

    /** Signed in, carrying both cookies, with the CSRF header set on every write. */
    protected WebTestClient farm;

    private final List<UUID> registered = new ArrayList<>();
    private final List<MockPrinter> machines = new ArrayList<>();
    private final List<Events> streams = new ArrayList<>();
    private final List<UUID> uploaded = new ArrayList<>();

    /** Which mock is behind which machine, so a failure can report what reached the far end. */
    private final Map<UUID, MockPrinter> attached = new java.util.HashMap<>();

    @BeforeEach
    void signIn() {
        var anonymous = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                // The test client buffers a response to assert on it; the server never does.
                // GcodeFileController streams a download straight out, so the default 256 KiB
                // ceiling would silently bound what a fixture may weigh.
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
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
     * Gives back everything the test took: the streams, the machines, the sockets. Deleting the
     * printer is what releases its runtime - {@code MDeletedEvent} reaches
     * {@code PrinterReactorManager.release} - and closing the mock without that leaves a reactor
     * redialling a dead port for the rest of the suite. Uploaded files go too: the store is
     * content-addressed over one shared database, so a leftover is a row every later
     * {@code GET /api-files} sees and a blob every later upload of the same bytes is handed back.
     */
    @AfterEach
    void tidyUp() {
        streams.forEach(Events::close);
        streams.clear();
        for (var id : uploaded) {
            try {
                farm.delete().uri("/api-files/{id}", id).exchange();
            } catch (RuntimeException e) {
                // Same as below: a failed cleanup must not mask the failure that matters.
            }
        }
        uploaded.clear();
        for (var id : registered) {
            try {
                farm.delete().uri("/api-manager/{id}", id).exchange();
            } catch (RuntimeException e) {
                // The farm is going away anyway; a failed cleanup must not mask the real failure.
            }
        }
        registered.clear();
        attached.clear();
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
        var id = register(name, "localhost", printer.port(), null);
        attached.put(id, printer);
        return id;
    }

    /** The same, declaring a spec - the half of a machine a person types rather than discovers. */
    protected UUID register(String name, MockPrinter printer, String specJson) {
        var id = register(name, "localhost", printer.port(), specJson);
        attached.put(id, printer);
        return id;
    }

    /** What reached the mock behind a machine, for a failure message. */
    protected String received(UUID id) {
        var printer = attached.get(id);
        return printer == null ? "(no mock is attached to this machine)"
                : printer.commands().toString();
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

    /**
     * Removes a machine from the farm mid-test, through the endpoint an administrator uses.
     *
     * <p>{@link #tidyUp()} deletes whatever is left at the end, which covers every test that only
     * ever adds. A test whose <em>subject</em> is the removal needs to do it itself and needs the
     * cleanup to then leave it alone: a second {@code DELETE} of the same id answers 404 harmlessly
     * but publishes nothing, so the difference would not be visible here - it would be visible on
     * the farm stream, as a second {@code MachineLeftFarm} for a machine that left once.
     */
    protected void deregister(UUID id) {
        farm.delete().uri("/api-manager/{id}", id).exchange().expectStatus().isOk();
        registered.remove(id);
        attached.remove(id);
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
     * {@code IDLE}, not {@code ONLINE}: an open socket proves reachable, not usable, and a command
     * sent after {@code ONLINE} races the handshake.
     */
    protected void connectAndWait(UUID id) {
        connect(id);
        await("machine " + id + " to reach IDLE", () -> "IDLE".equals(connectionState(id)));
    }

    /**
     * Dials the machine and waits until <strong>the aggregate has taken the firmware report</strong>,
     * handing back the stream it watched. Use this rather than {@link #connectAndWait(UUID)} for
     * anything depending on what the machine said it is - an extruder count, a capability.
     *
     * <p>Neither {@code IDLE} nor a later command reaching the wire is that precondition, and both
     * were used here. {@code IDLE} only means the first block of the session, {@code M110 N0}, was
     * acknowledged; {@code PrinterState.onOnline} queues {@code M115} behind it. And {@code M155 S1}
     * reaching the machine proves only that the {@code ok} closing {@code M115} was processed - on
     * the <em>netty inbound thread</em>, inside {@code CommandProcessor.handleBackpressure}, which
     * pulls and writes the next block directly, while the {@code FirmwareInfoRs} from the same reply
     * was queued onto the aggregate thread by {@code PrinterReactor}'s {@code publishOn(executor)}.
     * Two threads with no happens-before between them, so the next command can be on the wire before
     * the aggregate has looked at the report. Issue #54 changed how long that gap is, not that it
     * exists.
     *
     * <p>The guarantee is the report arriving on this machine's event stream: that emission is the
     * last thing {@code PrinterState.on(FirmwareInfoRs)} does. The stream is opened <em>before</em>
     * the dial because the per-printer sink replays only the latest message.
     */
    protected Events connectAndLearn(UUID id) {
        var events = watch(id);
        connect(id);
        await(() -> "machine " + id + " to reach IDLE. It is " + connectionState(id)
                        + ", the machine received " + received(id)
                        + " and the stream carried " + events.types(),
                () -> "IDLE".equals(connectionState(id)));
        await(() -> "the aggregate to take the firmware report."
                        + " The machine received " + received(id)
                        + " and the stream carried " + events.types()
                        + ". If M115 is in the first list and FirmwareInfoRs is not in the second,"
                        + " the reply was lost between the socket and the aggregate",
                () -> events.saw("FirmwareInfoRs"));
        return events;
    }

    /**
     * Dials the machine, waits for the opening handshake to be <em>over</em>, and clears the
     * command log, so what follows is exactly what the test's own request caused.
     *
     * <p>{@code connectAndWait} then {@code forget()} is the shape that broke on CI: {@code IDLE}
     * arrives when {@code M110 N0} is acknowledged, with {@code M115} and {@code M155 S1} still in
     * flight, so clearing there removes {@code M110} and lets the other two land in the cleared log
     * as the test's own - an exact-list assertion sees {@code [M115, M155 S1, M23 …, M24]}, and
     * {@code firstSent("M155")} returns the handshake's {@code M155 S1} rather than the
     * {@code M155 S3} asked for. Locally the handshake wins the race in microseconds; on a loaded
     * two-core runner it does not. {@code M155 S1} is the handshake's last block - see
     * {@code PrinterState.onOnline} - so its arrival is the honest signal.
     */
    protected Events connectAndSettle(UUID id, MockPrinter printer) {
        var events = connectAndLearn(id);
        await(() -> "the opening handshake to finish."
                        + " The machine received " + printer.commands(),
                () -> printer.sent("M155 S1"));
        printer.forget();
        return events;
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

    // ------------------------------------------------------------------ the file store

    /**
     * Puts a file into the farm's store and hands back the record it became. Shared because a print
     * job (#51) and an SD upload (#53) both need one there first, and putting it there by any other
     * means is what §3 of the integration-test spec forbids. The request is the browser's -
     * {@code client.ts}'s {@code files.upload}: raw bytes, the name in {@code X-File-Name}, not
     * multipart.
     */
    protected Map<String, Object> upload(GcodeFixture file) {
        return upload(file.name(), file.bytes());
    }

    /** The same, for a test that wants to decide the bytes itself. */
    protected Map<String, Object> upload(String name, byte[] content) {
        var stored = json(farm.post().uri("/api-files")
                .header("X-File-Name", name)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(content)
                .exchange()
                .expectStatus().isOk());
        uploaded.add(idOf(stored));
        return stored;
    }

    /** Every file the farm holds, as a browser's file picker reads it. */
    protected List<Map<String, Object>> storedFiles() {
        return jsonList(farm.get().uri("/api-files").exchange().expectStatus().isOk());
    }

    /** The {@code id} of a record the server returned, which is a string on the wire. */
    protected static UUID idOf(Map<String, Object> record) {
        var id = record.get("id");
        assertNotNull(id, "the server returned a record with no id: " + record);
        return UUID.fromString((String) id);
    }

    // ------------------------------------------------------------------ the event stream

    /** Opens this machine's SSE channel and starts recording what a browser would receive. */
    protected Events watch(UUID id) {
        var events = new Events("/api-printer/" + id + "/stream");
        streams.add(events);
        return events;
    }

    /**
     * Opens the farm-wide channel - the one a dashboard holds instead of one per machine.
     *
     * <p><strong>Register the machines first.</strong> A subscriber first receives a snapshot of the
     * machines the farm knows about at that moment, and nothing at all when it knows of none, so
     * opening early gives a stream that is correct and silent. That snapshot entry is also the only
     * guarantee the subscription is <em>live</em> - the live sink is subscribed before the snapshot
     * is built - so wait for it before causing anything, and cause it with a request.
     */
    protected Events watchTheFarm() {
        var events = new Events("/api-farm/stream");
        streams.add(events);
        return events;
    }

    /**
     * An open SSE subscription, recording what arrived: one {@code EventMessage} as JSON per
     * element - {@code id}, {@code type}, the event. {@code type} is the event's <em>simple class
     * name</em>, so it is browser-visible and a rename in {@code :gcode} changes it; a test naming
     * one pins the contract {@code EventStreamContractTest} pins from the other end. Decoded into a
     * map rather than bound to {@code EventMessage}, because the browser has no such class and
     * binding would let a field that never reaches the wire pass unnoticed.
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

        /**
         * The payload of <strong>every</strong> message of this type, oldest first.
         * {@link #payloadOf(String)} keeps only the last, which is right for a restated state and
         * wrong for a reply arriving many times as one answer: an {@code M503} report is
         * twenty-five messages of two types, and reading only the last {@code SettingsValue} would
         * pass against a host that dropped the other twelve.
         */
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> allOf(String type) {
            return seen.stream()
                    .filter(message -> type.equals(message.get("type")))
                    .map(message -> (Map<String, Object>) message.get("event"))
                    .toList();
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

        // -------------------------------------------------------------- one stream, many machines

        /**
         * The same, narrowed to one machine - which on {@code /api-farm/stream} is the whole point.
         *
         * <p>The accessors above ignore {@code id} because every stream they were written for
         * carries one machine. The farm-wide stream carries all of them, and a dashboard routes on
         * {@code EventMessage.id} before it switches on {@code type}; asserting that routing means
         * asking the same questions per machine. A test that only asked "did the stream carry an
         * {@code OkTemperatureRs}" would pass on a farm that put every machine's reading under one
         * id, which is exactly the failure one stream can have and several cannot.
         *
         * <p>{@code id} is a string on the wire, as a browser receives it.
         */
        public List<Map<String, Object>> from(UUID machine) {
            var wanted = machine.toString();
            return seen.stream().filter(m -> wanted.equals(m.get("id"))).toList();
        }

        /** The types that arrived under one machine, oldest first. */
        public List<String> typesFrom(UUID machine) {
            return from(machine).stream().map(m -> (String) m.get("type")).toList();
        }

        /** The first message that arrived under one machine, or null - the snapshot entry. */
        public Map<String, Object> firstFrom(UUID machine) {
            var messages = from(machine);
            return messages.isEmpty() ? null : messages.getFirst();
        }

        public boolean saw(UUID machine, String type) {
            return typesFrom(machine).contains(type);
        }

        /** Waits for a message of this type under this machine, and hands back its payload. */
        public Map<String, Object> await(UUID machine, String type) {
            FarmIntegrationTest.await(() -> "an SSE message of type " + type + " for machine "
                    + machine + ". The stream carried " + byMachine(), () -> saw(machine, type));
            return payloadOf(machine, type);
        }

        /** The payload of the most recent message of this type under this machine, or null. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> payloadOf(UUID machine, String type) {
            Map<String, Object> last = null;
            for (var message : from(machine)) {
                if (type.equals(message.get("type"))) {
                    last = (Map<String, Object>) message.get("event");
                }
            }
            return last;
        }

        /**
         * What arrived under each machine, in order - the evidence a demultiplexing failure needs.
         *
         * <p>{@link #types()} flattens the two machines together, which is the one thing a failure
         * message here must not do: "the stream carried two {@code ConnectionStateChanged}" is true
         * both when each machine announced itself once and when one announced itself twice while the
         * other was never heard from. Grouped by id, those read differently at a glance.
         */
        public Map<String, List<String>> byMachine() {
            var grouped = new java.util.LinkedHashMap<String, List<String>>();
            for (var message : seen) {
                grouped.computeIfAbsent((String) message.get("id"), any -> new ArrayList<String>())
                        .add((String) message.get("type"));
            }
            return grouped;
        }

        @Override
        public void close() {
            subscription.dispose();
        }
    }

    // ------------------------------------------------------------------ waiting, and reading JSON

    /** Waits for something to become true, and says what it was waiting for when it does not. */
    protected static void await(String what, BooleanSupplier condition) {
        await(() -> what, PATIENCE, condition);
    }

    /**
     * The same, with the description built only if it is needed.
     *
     * <p>Worth the overload because the useful description is the <em>evidence</em> - which events
     * arrived, what reached the machine - and computing that eagerly would snapshot it before the
     * wait rather than after it, which is the opposite of what a failure needs. A CI failure that
     * cannot be reproduced locally is diagnosed by what its message carries and nothing else.
     */
    protected static void await(Supplier<String> what, BooleanSupplier condition) {
        await(what, PATIENCE, condition);
    }

    /**
     * A bounded pause, permitted <strong>only where the claim is that something did not happen</strong>.
     *
     * <p>§3.2 of the integration-test spec: waiting a fixed time for a <em>result</em> is a test that
     * is slow when it passes and flaky when it fails, so everything else uses {@link #await}. A
     * negative has no condition to poll - "no command reached the machine", "no socket was opened" -
     * and the only honest way to state it is to give the thing long enough to have happened.
     *
     * <p>Long enough is measured against the positive case: a dial, a handshake and a round trip on
     * loopback are a few milliseconds, so three quarters of a second is two orders of magnitude of
     * room. It is one helper rather than one per test class so that the figure, and the rule about
     * when it may be used at all, have a single place to be argued about.
     */
    protected static void quiet() {
        try {
            Thread.sleep(750);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits for the conversation to reach the block the machine stopped answering at, and then for
     * it to <em>stop</em>. Answers with how many commands are on the wire.
     *
     * <p><strong>That number is the basis of every test that samples a stream mid-flight</strong> -
     * a print being preempted, a transfer being cancelled - so it has to be one that cannot move
     * afterwards. It is: the machine is no longer acknowledging and the send window will not pull
     * another block until one comes back, so whatever the window still had room for lands, and then
     * nothing further can reach the machine at all. That is the send window and not a clock, which
     * is what makes it honest on a loaded runner.
     *
     * <p>Waited out until the count stops moving rather than for one fixed pause. A fixed pause is
     * what §3.2 of the integration-test spec permits for a claim that something did not happen, and
     * that is the claim here - but on a two-core runner the last of those writes can take longer
     * than any single pause worth choosing, and the failure would be a wrong number rather than a
     * timeout. Two consecutive unchanged samples is the same claim at whatever speed the machine is
     * running, and it is <em>asserted</em>: a conversation that never stops is a failure with the
     * evidence in its message, not a test that quietly reads the wrong thing.
     *
     * <p>Here rather than in one test class because two features need it and for the reason
     * {@link #quiet()} is here: "how long is long enough" is a question to answer once.
     */
    protected static int stalled(MockPrinter printer, String stopsAt) {
        await(() -> "the wire to reach " + stopsAt + ". The machine received " + printer.commands(),
                () -> printer.sent(stopsAt));

        var deadline = System.nanoTime() + PATIENCE.toNanos();
        int sampled = -1;
        int unchanged = 0;
        while (unchanged < 2 && System.nanoTime() < deadline) {
            quiet();
            int now = printer.commands().size();
            unchanged = now == sampled ? unchanged + 1 : 0;
            sampled = now;
        }
        assertTrue(unchanged >= 2, "the machine stopped answering at " + stopsAt
                + ", so the conversation should have stopped and it has not: " + printer.commands());
        return sampled;
    }

    /** JSON has one number type; which Java box it arrives in depends on its magnitude. */
    protected static long number(Object value) {
        assertNotNull(value, "expected a number");
        return ((Number) value).longValue();
    }

    protected static void await(Supplier<String> what, Duration limit, BooleanSupplier condition) {
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
        fail("timed out after " + limit + " waiting for " + what.get());
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

    /** The same for the endpoints that answer with an array - a listing, a roster, a queue. */
    protected static List<Map<String, Object>> jsonList(WebTestClient.ResponseSpec response) {
        var body = response.expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() {
        }).returnResult().getResponseBody();
        assertNotNull(body, "expected a JSON array in the response body");
        return body;
    }
}
