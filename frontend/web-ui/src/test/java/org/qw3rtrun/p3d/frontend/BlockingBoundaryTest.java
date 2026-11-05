package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.frontend.mvc.MvcPrinterController;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.ui.ConcurrentModel;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every blocking call this module makes runs somewhere it is allowed to block.
 *
 * <p>WebFlux serves requests on a handful of Netty event-loop threads and Spring Data JPA is
 * blocking JDBC; putting the second inside the first stalls every other request sharing that
 * thread, with no symptom until the farm is under load - which is how this layer carried the bug
 * in four controllers at once.
 *
 * <p>What is asserted is the thread the service was called on, not the shape of the code: a
 * {@code Mono.fromSupplier(…)} with no {@code subscribeOn} looks asynchronous and is not, since a
 * {@code Mono} is lazy and its supplier runs on whoever subscribes. These tests record
 * {@code Thread.currentThread()} from inside a fake service and insist on a {@code boundedElastic}
 * worker, so the "looks async" version fails here.
 *
 * <p>The printer-control side is not here any more: opening a runtime still runs off the caller's
 * thread, but that moved into {@code core} along with the control interface, so
 * {@code PrinterControlServiceTest} asserts it now.
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
            new Descriptor(UUID.randomUUID(), "a printer", new ConnectionDetails("localhost", 1),
                    MachineConnectionState.OFFLINE,
                    MachineProfile.unknown(), MachineSpec.undeclared());

    /** A management service that does nothing but remember which thread asked. */
    private MachineManagementService recordingService() {
        return new MachineManagementService() {
            @Override
            public Optional<Descriptor> fetch(UUID uuid) {
                record();
                return Optional.of(descriptor);
            }

            @Override
            public Descriptor add(String name, ConnectionDetails connection,
                                  MachineSpec spec) {
                record();
                return descriptor;
            }

            @Override
            public Descriptor update(UUID uuid, String name, ConnectionDetails connection,
                                     MachineSpec spec) {
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

    // create was the subtlest of the four: it was payload.map(...), so the insert ran on the event
    // loop that delivered the request body, with no Mono wrapper to make it look suspicious.
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

    // The server-rendered pages, called directly rather than through a view resolver, since what is
    // under test is where the query runs and that needs no Thymeleaf configured.
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

        controller.postAdd(machine).block();

        assertRanOffTheCallersThread();
    }
}
