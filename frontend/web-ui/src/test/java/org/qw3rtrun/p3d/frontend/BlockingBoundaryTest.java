package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.core.PrinterReactorManager;
import org.qw3rtrun.p3d.core.PrinterState;
import org.qw3rtrun.p3d.frontend.mvc.MvcPrinterController;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.ui.ConcurrentModel;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every blocking call this module makes runs somewhere it is allowed to block.
 *
 * <p>WebFlux serves requests on a handful of Netty event-loop threads and Spring Data JPA is
 * blocking JDBC. Putting the second inside the first stalls every other request sharing that
 * thread, and it has no symptom at all until the farm is under load - which is why this layer
 * carried the bug in four controllers at once and nothing noticed.
 *
 * <p><strong>What is asserted is the thread the service was called on</strong>, not the shape of
 * the code. A {@code Mono.fromSupplier(…)} with no {@code subscribeOn} looks asynchronous and is
 * not: a {@code Mono} is lazy, so its supplier runs on whoever subscribes. These tests record
 * {@code Thread.currentThread()} from inside a fake service and insist on a {@code boundedElastic}
 * worker, so the "looks async" version fails here - the recorded thread would be the caller's.
 *
 * <p>This is the check that catches the next one. A new handler that reaches the management
 * service without scheduling it has to break one of these.
 */
class BlockingBoundaryTest {

    private final AtomicReference<String> calledOn = new AtomicReference<>();

    private void record() {
        calledOn.set(Thread.currentThread().getName());
    }

    private void assertRanOffTheCallersThread() {
        var thread = calledOn.get();
        assertNotNull(thread, "the service was never called, so the test proved nothing");
        assertTrue(thread.startsWith("boundedElastic"),
                "blocking work ran on '" + thread + "' - it must be scheduled onto boundedElastic,"
                        + " and Mono.fromSupplier alone does not do that");
    }

    private final Descriptor descriptor =
            new Descriptor(UUID.randomUUID(), "a printer", new ConnectionDetails("localhost", 1), false);

    /** A management service that does nothing but remember which thread asked. */
    private MachineManagementService recordingService() {
        return new MachineManagementService() {
            @Override
            public Descriptor fetch(UUID uuid) {
                record();
                return descriptor;
            }

            @Override
            public Descriptor add(String name, ConnectionDetails connection) {
                record();
                return descriptor;
            }

            @Override
            public Stream<Descriptor> fetchAll() {
                record();
                return Stream.of(descriptor);
            }

            @Override
            public void delete(UUID uuid) {
                record();
            }

            @Override
            public void disconnect(UUID uuid) {
                record();
            }

            @Override
            public void connect(UUID uuid) {
                record();
            }
        };
    }

    private WebTestClient management() {
        return WebTestClient.bindToController(new FDMPrinterManagementController(recordingService())).build();
    }

    @Test
    void fetchingOnePrinterRunsOffTheCallersThread() {
        management().get().uri("/api-manager/" + descriptor.uuid()).exchange().expectStatus().isOk();

        assertRanOffTheCallersThread();
    }

    @Test
    void listingPrintersRunsOffTheCallersThread() {
        management().get().uri("/api-manager").exchange().expectStatus().isOk();

        assertRanOffTheCallersThread();
    }

    /**
     * The write paths too.
     *
     * <p>{@code create} was the subtlest of the four: it was {@code payload.map(…)}, so the insert
     * ran on whichever thread delivered the request body - the event loop - with no {@code Mono}
     * wrapper anywhere to make it look suspicious.
     */
    @Test
    void creatingAPrinterRunsOffTheCallersThread() {
        management().post().uri("/api-manager")
                .header("Content-Type", "application/json")
                .bodyValue("{\"name\":\"x\",\"host\":\"localhost\",\"port\":8099}")
                .exchange().expectStatus().isOk();

        assertRanOffTheCallersThread();
    }

    @Test
    void deletingAPrinterRunsOffTheCallersThread() {
        management().delete().uri("/api-manager/" + descriptor.uuid()).exchange().expectStatus().isOk();

        assertRanOffTheCallersThread();
    }

    /**
     * The reactor lookup, which is the one that was never even lazy.
     *
     * <p>{@code PrinterReactorManager.openReactor} reads the printer's row, builds a reactor and -
     * on a row that says connected - opens a socket, all inside a {@code synchronized} block. Every
     * handler called it directly in its own body, so it ran eagerly on the event loop before the
     * returned publisher existed.
     */
    @Test
    void theReactorLookupRunsOffTheCallersThread() {
        var manager = new PrinterReactorManager(recordingService(), PrinterState::new);

        WebTestClient.bindToController(new PrinterController(recordingService(), manager)).build()
                .get().uri("/api-printer/" + descriptor.uuid()).exchange().expectStatus().isOk();

        assertRanOffTheCallersThread();
    }

    /**
     * The server-rendered pages, called directly rather than through a view resolver.
     *
     * <p>What is under test is where the query runs, and that does not need Thymeleaf to be
     * configured. These handlers returned {@code Mono<String>} while calling the repository from
     * the method body - a signature that looks reactive around work that is not.
     */
    @Test
    void theDashboardQueryRunsOffTheCallersThread() {
        var controller = new MvcPrinterController(recordingService());
        var model = new ConcurrentModel();

        controller.dashboard(model).block();

        assertRanOffTheCallersThread();
    }

    @Test
    void addingAPrinterFromThePageRunsOffTheCallersThread() {
        var controller = new MvcPrinterController(recordingService());
        var machine = new MvcPrinterController.MachineInfo();
        machine.setName("x");
        machine.setHost("localhost");
        machine.setPort(8099);

        controller.postAdd(new ConcurrentModel(), machine).block();

        assertRanOffTheCallersThread();
    }
}
