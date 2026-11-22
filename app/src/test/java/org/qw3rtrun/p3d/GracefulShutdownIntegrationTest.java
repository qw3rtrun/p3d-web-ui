package org.qw3rtrun.p3d;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The farm lets go of its machines when the application is told to stop - issue #87.
 *
 * <p>Nothing used to release a printer's runtime when the context closed -
 * {@code PrinterReactorManager.release} was reached only from {@code MDeletedEvent} - so a farm
 * shut down with machines connected left every socket open, every send window mid-flight and every
 * per-printer scheduler alive. Measured against the simulator on {@code localhost:8099} before any
 * of this was written: the machine's auto-reported temperatures kept arriving <strong>after</strong>
 * the context had closed its datasource -
 *
 * <pre>
 * 12:47:26.392  INFO [      Thread-21] com.zaxxer.hikari.HikariDataSource  : HikariPool-1 - Shutdown completed.
 * 12:47:26.832  INFO [actor-tcp-nio-2] org.qw3rtrun.p3d.terminal.HostTerminal : IN: onNext(BareTemperatureRs(...))
 * </pre>
 *
 * and no {@code releasing the runtime of <id>} line appeared anywhere in the shutdown.
 *
 * <p>Graceful shutdown is on by default in Spring Boot 4 and waits for in-flight requests; an SSE
 * subscription never finishes on its own, and the farm-wide stream had no end-of-stream marker at
 * all - so with a dashboard open the web server waited out the whole
 * {@code spring.lifecycle.timeout-per-shutdown-phase} (thirty seconds) and gave up:
 *
 * <pre>
 * 12:43:08.545  Commencing graceful shutdown. Waiting for active requests to complete
 * 12:43:38.548  Shutdown phase 2147482623 ends with 1 bean still running after timeout of 30000ms: [webServerGracefulShutdown]
 * 12:43:38.550  Graceful shutdown aborted with one or more requests still active
 * </pre>
 *
 * <p>Measured end to end with a real {@code Ctrl+C}: <strong>32.6 seconds</strong> with two streams
 * open against <strong>2.1 seconds</strong> with none.
 *
 * <p>What this test asserts, and why each is the observable: the close returns promptly (bounded
 * well below the thirty-second phase timeout); each stream <em>completes</em> rather than being
 * severed (a dropped connection would also be fast but wrong - a browser would see an error and an
 * {@code EventSource} would reconnect; {@code onComplete} is the difference, recorded by the two
 * latches below); and the machine's socket is shut, which {@code MockPrinter.sessionOpen()} proves
 * from the one side that cannot be satisfied by tidying up locally.
 *
 * <p>This test boots its own context rather than sharing {@code FarmIntegrationTest}'s cached one,
 * because its subject <em>is</em> closing the context - a unit test of the lifecycle bean would not
 * have caught either half of #87. The sign-in is repeated here, deliberately the browser's own flow
 * ({@code /login} for {@code XSRF-TOKEN}, post the form, keep {@code SESSION}), because an SSE
 * subscription is authenticated by that cookie and nothing else.
 */
class GracefulShutdownIntegrationTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * Well under the thirty seconds {@code spring.lifecycle.timeout-per-shutdown-phase} allows a
     * phase - spending that in full <em>is</em> the bug - but wide enough that a loaded build agent
     * closing a JPA context, a Hikari pool and a netty server does not fail it.
     */
    private static final Duration PROMPTLY = Duration.ofSeconds(15);

    /** Long enough for a dial and a handshake on a loaded build agent - {@code FarmIntegrationTest}'s figure. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /**
     * Two streams, not one, because they fail for different reasons: the per-printer stream had an
     * end-of-stream marker a <em>deleted</em> printer already produced, one call from working; the
     * farm stream had none at all. Asserting only the first would leave a dashboard - the stream a
     * farm has open all day - holding the shutdown open for thirty seconds.
     */
    @Test
    void aFarmWithAConnectedMachineAndOpenStreamsStopsWhenItIsToldTo() throws Exception {
        try (var printer = MockPrinter.listening(MockPrinter.marlin())) {
            var context = boot();
            var port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var session = signIn(port);
            var client = session.farm();

            var id = register(client, "the one that was running when we stopped", printer.port());
            connectAndWait(client, id);
            assertTrue(printer.sessionOpen(), "the machine should have a session before we stop anything");

            var machineStream = session.subscribe("/api-printer/" + id + "/stream");
            var farmStream = session.subscribe("/api-farm/stream");
            machineStream.awaitFirstMessage();
            farmStream.awaitFirstMessage();

            var started = System.nanoTime();
            context.close();
            var took = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(took.compareTo(PROMPTLY) < 0,
                    "closing the context took " + took.toMillis() + "ms, which is the hang of #87: "
                            + "the web server spends the whole of spring.lifecycle.timeout-per-shutdown-phase "
                            + "waiting for SSE subscriptions that never end");

            machineStream.assertCompleted("the machine's own stream");
            farmStream.assertCompleted("the farm-wide stream");

            await("the machine's socket to be shut by the host, not by the process dying",
                    () -> !printer.sessionOpen());
        }
    }

    /**
     * Deleting a machine reaches {@code PrinterReactorManager.release} then
     * {@code PrinterReactor.stop()}, which parks a {@code StreamEnd} on the outbox; the subscriber
     * is told once it drains. The web server is still standing throughout, so a severed stream here
     * would be an outright defect - a page watching a deleted printer deserves to be told, not
     * dropped.
     *
     * <p>The subscriber is paced deliberately, one element with a pause, because an unpaced one
     * consumes faster than the handshake is produced and never has a backlog to be cut off mid-way -
     * this shape is what found the real ordering bug in the first place.
     *
     * <p>Deliberately not asserted: the same paced subscriber during a <em>shutdown</em> reliably
     * ends in {@code PrematureCloseException: Connection prematurely closed DURING response}.
     * Chased to its boundary and left there: the server completes the response from this machine's
     * own thread, and reactor-netty schedules the final channel write onto the connection's event
     * loop rather than inline, so the last flush can lose a race with
     * {@code WebServerGracefulShutdownLifecycle} tearing the server down - invisible to any witness
     * inside {@code :backend:core}. It is also not a difference the client can observe:
     * {@code EventSource} has no completion callback (see {@code subscribe}'s note in
     * {@code client.ts}), so a browser answers a clean finish and a reset the same way,
     * {@code onerror} then a reconnect. What #87 owes a browser is that the farm stops promptly and
     * holds nothing in flight, which the test above measures.
     */
    void aDeletedPrinterSaysGoodbyeToASubscriberThatIsBehind() throws Exception {
        try (var printer = MockPrinter.listening(MockPrinter.marlin())) {
            var context = boot();
            try {
                var port = ((WebServerApplicationContext) context).getWebServer().getPort();
                var session = signIn(port);
                var client = session.farm();

                var id = register(client, "the one that was deleted while watched", printer.port());
                connectAndWait(client, id);

                var machineStream = session.subscribeSlowly("/api-printer/" + id + "/stream");
                machineStream.awaitFirstMessage();

                client.delete().uri("/api-manager/{id}", id).exchange().expectStatus().isOk();

                machineStream.assertCompleted("the deleted machine's stream, read slowly");
            } finally {
                context.close();
            }
        }
    }

    /**
     * <strong>Killing the farm used to leave every connected machine still talking to nobody</strong> -
     * issue #127's third item.
     *
     * <p>{@code PrinterState.handle(ConnectCmd)} has turned the temperature report off before a
     * <em>voluntary</em> disconnect for a long time, but {@code FarmShutdown} never took that path:
     * it called {@code PrinterReactorManager.releaseAll()}, which stopped each reactor directly.
     * {@code PrinterReactor.stop()} disconnects and closes the socket, and nothing in between asked
     * the aggregate to say goodbye. So a {@code Ctrl+C} left every printer in the farm emitting an
     * unsolicited {@code  T:25.00 /0.00 ...} once a second into a closed socket until someone
     * power-cycled it or another host connected and happened to send {@code M155 S0}.
     *
     * <p>The observable is the machine's own command log, read after the context has closed - the
     * same far boundary {@code HandshakeIntegrationTest} uses, and the only one that can tell
     * "the host decided to send it" from "the bytes arrived".
     *
     * <p>Two auto-reports and not one, deliberately: {@code withPositionAutoReport()} is started
     * {@code M154 S1} as well as {@code M155 S1}, so the assertion is that the teardown undoes
     * <em>what this session started</em> rather than one hard-coded off-switch.
     */
    @Test
    void aFarmShutdownTurnsOffTheAutoReportsItStarted() throws Exception {
        try (var printer = MockPrinter.listening(MockPrinter.withPositionAutoReport())) {
            var context = boot(freshDatabase());
            var port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var client = signIn(port).farm();

            var id = register(client, "still reporting when we stopped", printer.port());
            connectAndWait(client, id);
            await("the opening handshake to finish",
                    () -> printer.commands().contains("M154 S1"));
            printer.forget();

            context.close();

            await(() -> "the farm to have turned its reports off before closing the socket."
                            + " The machine received " + printer.commands(),
                    () -> printer.commands().containsAll(List.of("M155 S0", "M154 S0")));
            // M27 is SdStatusPoll's own housekeeping (#147), filtered for the same reason
            // FarmIntegrationTest.withoutSdStatusPoll exists - this test does not share that base
            // class, so the one line is repeated rather than reached for across modules.
            assertEquals(List.of("M155 S0", "M154 S0"),
                    printer.commands().stream().filter(c -> !c.equals("M27")).toList(),
                    "a shutdown turns off exactly what the session turned on");
            await("the socket to be shut", () -> !printer.sessionOpen());
        }
    }

    /**
     * <strong>And the goodbye must not be mistaken for a decision.</strong>
     *
     * <p>The cheap way to fix the test above is to have {@code releaseAll()} send every reactor a
     * {@code ConnectCmd(false)}, which already runs the teardown. It would pass, and it would break
     * something else silently: a voluntary disconnect ends the <em>intent</em> to be connected,
     * {@code printer_entity.connection_state} records that, and
     * {@code PrinterReactorManager.onRegistered} dials at startup only what the roster says was
     * linked. So a farm shut down that way comes back up with every machine offline and nothing
     * redialling it - the whole of the restart-recovery feature, lost to a shutdown tidy-up.
     *
     * <p>Hence {@code PrinterState.quiesce()} deliberately touches no connection state. This is that
     * claim, stated the only way it can honestly be stated: a second farm, booted on the same
     * database, must dial the machine again on its own. The mock's session count is the evidence,
     * because it is the one thing neither context can fake.
     */
    @Test
    void aFarmThatSaidGoodbyeStillDialsTheMachineAgainWhenItComesBack() throws Exception {
        try (var printer = MockPrinter.listening(MockPrinter.marlin())) {
            var database = freshDatabase();
            var first = boot(database);
            var port = ((WebServerApplicationContext) first).getWebServer().getPort();
            var client = signIn(port).farm();
            var id = register(client, "connected across a restart", printer.port());
            connectAndWait(client, id);
            await("the first session's handshake", () -> printer.sent("M155 S1"));

            first.close();
            await("the first session to end", () -> !printer.sessionOpen());
            printer.forget();

            var second = boot(database);
            try {
                await(() -> "the restarted farm to dial the machine again. It has seen "
                                + printer.sessions() + " session(s) and received "
                                + printer.commands(),
                        () -> printer.sessions() == 2 && printer.sent("M155 S1"));
                assertEquals("M110 N0", printer.commands().getFirst(),
                        "the second farm's session must open its own numbering");
            } finally {
                second.close();
            }
        }
    }

    // ------------------------------------------------------------------ the farm, booted here

    /**
     * A fresh database name per run, because {@code application-test.properties} points the cached
     * context at {@code jdbc:h2:mem:apptestdb} and two contexts migrating one schema is a race this
     * test has no reason to take. {@code DB_CLOSE_DELAY=-1} keeps it alive across a boot's
     * connection-pool churn and is discarded when the JVM exits.
     */
    private static ConfigurableApplicationContext boot() {
        return boot(freshDatabase());
    }

    /**
     * The same, on a named database - so two contexts in sequence can be the <em>same farm</em>
     * restarted, which is what {@link #aFarmThatSaidGoodbyeStillDialsTheMachineAgainWhenItComesBack()}
     * needs. {@code DB_CLOSE_DELAY=-1} is what makes the rows outlive the first context.
     */
    private static ConfigurableApplicationContext boot(String datasourceUrl) {
        return new SpringApplicationBuilder(StandaloneApp.class)
                .profiles("test")
                .properties("server.port=0", "spring.datasource.url=" + datasourceUrl)
                .run();
    }

    private static String freshDatabase() {
        return "jdbc:h2:mem:shutdown-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    /**
     * A signed-in browser: the client the test drives, and the two cookies an SSE subscription needs
     * to carry on its own - {@code WebTestClient} keeps its default cookies to itself, and the
     * streams below use a plain {@code WebClient} (see {@link Stream}), so the values must be held
     * separately.
     */
    private record Signed(WebTestClient farm, int port, String csrf, String session) {

        /**
         * The same subscription, consumed one element with a pause between - a browser on a slow
         * link, or a throttled tab. {@code BaseSubscriber} rather than a {@code Flux} operator
         * because the point is the <em>demand</em>: requesting one, sleeping, then one more leaves
         * the server's {@code publishOn} with nothing it is allowed to emit, the condition the test
         * above probes for.
         */
        Stream subscribeSlowly(String path) {
            var first = new CountDownLatch(1);
            var done = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();
            var subscriber = new BaseSubscriber<String>() {
                @Override
                protected void hookOnSubscribe(Subscription subscription) {
                    request(1);
                }

                @Override
                protected void hookOnNext(String message) {
                    first.countDown();
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    request(1);
                }

                @Override
                protected void hookOnError(Throwable failure) {
                    error.set(failure);
                    first.countDown();
                    done.countDown();
                }

                @Override
                protected void hookOnComplete() {
                    done.countDown();
                }
            };
            stream(path).subscribe(subscriber);
            return new Stream(first, done, error, subscriber);
        }

        private Flux<String> stream(String path) {
            return WebClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .build()
                    .get().uri(path)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .cookie("SESSION", session)
                    .cookie("XSRF-TOKEN", csrf)
                    .retrieve()
                    .bodyToFlux(String.class);
        }

        Stream subscribe(String path) {
            var first = new CountDownLatch(1);
            var done = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();
            var subscription = stream(path).subscribe(
                    message -> first.countDown(),
                    failure -> {
                        error.set(failure);
                        first.countDown();
                        done.countDown();
                    },
                    done::countDown);
            return new Stream(first, done, error, subscription);
        }
    }

    private static Signed signIn(int port) {
        var anonymous = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(PATIENCE)
                .build();

        var login = anonymous.get().uri("/login").exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        var token = login.getResponseCookies().getFirst("XSRF-TOKEN");
        assertNotNull(token, "the login page must issue a CSRF cookie or no form can be posted");
        var csrf = token.getValue();

        var form = new LinkedMultiValueMap<String, String>();
        form.add("username", "admin");
        form.add("password", "integration-test-admin");
        form.add("_csrf", csrf);

        var result = anonymous.post().uri("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .cookie("XSRF-TOKEN", csrf)
                .body(BodyInserters.fromFormData(form))
                .exchange()
                .returnResult(String.class);
        assertEquals(302, result.getStatus().value(), "the farm's own administrator could not sign in");
        var session = result.getResponseCookies().getFirst("SESSION");
        assertNotNull(session, "a successful sign-in must establish a session");

        var farm = anonymous.mutate()
                .defaultCookie("XSRF-TOKEN", csrf)
                .defaultCookie("SESSION", session.getValue())
                .defaultHeader("X-XSRF-TOKEN", csrf)
                .build();
        return new Signed(farm, port, csrf, session.getValue());
    }

    private static UUID register(WebTestClient farm, String name, int port) {
        var body = "{\"name\":\"" + name + "\",\"host\":\"localhost\",\"port\":" + port + "}";
        var machine = json(farm.post().uri("/api-manager")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk());
        return UUID.fromString((String) machine.get("uuid"));
    }

    private static void connectAndWait(WebTestClient farm, UUID id) {
        farm.post().uri("/api-printer/{id}/connect", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"connect\":true}")
                .exchange()
                .expectStatus().isOk();
        await("the machine to come online", () -> {
            var status = json(farm.get().uri("/api-printer/{id}/connection", id)
                    .exchange().expectStatus().isOk());
            return !"OFFLINE".equals(status.get("state")) && !"CONNECTING".equals(status.get("state"));
        });
    }

    // ------------------------------------------------------------------ an SSE subscription, watched

    /**
     * A real {@code WebClient}, not {@code WebTestClient}: this must stay open when the close
     * begins and record its <em>terminal signal</em>, where {@code WebTestClient} buffers a
     * response and hands it back once finished - the one thing a never-ending stream never does.
     */
    private record Stream(CountDownLatch first, CountDownLatch done,
                          AtomicReference<Throwable> error, Disposable subscription) {

        void awaitFirstMessage() throws InterruptedException {
            assertTrue(first.await(PATIENCE.toSeconds(), TimeUnit.SECONDS),
                    "nothing arrived on the stream, so this test would not have been holding one open");
        }

        /**
         * An error here means the wrong fix was made - severed rather than finished, which a
         * browser shows as a failure and an {@code EventSource} answers by reconnecting to a
         * server going away.
         */
        void assertCompleted(String what) throws InterruptedException {
            assertTrue(done.await(PROMPTLY.toSeconds(), TimeUnit.SECONDS),
                    what + " never terminated, so the request the web server was waiting for is still in flight");
            assertNull(error.get(),
                    what + " was cut off rather than completed: " + error.get());
            subscription.dispose();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static void await(String what, BooleanSupplier condition) {
        await(() -> what, condition);
    }

    /** The same, with the description built only when the wait fails - the evidence is the point. */
    private static void await(Supplier<String> what, BooleanSupplier condition) {
        var deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what.get());
            }
        }
        fail("waited " + PATIENCE.toSeconds() + "s for " + what.get());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(WebTestClient.ResponseSpec response) {
        var body = response.expectBody(String.class).returnResult().getResponseBody();
        try {
            return JSON.readValue(body, Map.class);
        } catch (Exception malformed) {
            throw new IllegalStateException("not JSON: " + body, malformed);
        }
    }
}
