package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.files.SliceInfo;
import org.reactivestreams.Publisher;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Asking for a file the farm does not hold is a 404, and asking for one it does is still a 200.
 *
 * <p>An empty {@code Mono} out of a {@code @RestController} handler is 200 with a zero-length body,
 * which is the bug this pins: the browser's {@code getJson} calls {@code response.json()} on
 * whatever comes back, so "no such file" arrived at the UI as a JSON syntax error rather than a
 * status. This handler's neighbours, {@code download} and {@code JobController.fetch}, already
 * answered 404 - this one was the odd one out.
 *
 * <p>Driven through {@link WebTestClient#bindToController} against a hand-written service, the way
 * {@code BlockingBoundaryTest} does, since booting a context would prove the same thing more slowly.
 */
class GcodeFileFetchStatusTest {

    private final GcodeFile held = new GcodeFile(UUID.randomUUID(), "benchy.gcode",
            "a".repeat(64), 1024L, 42L,
            LocalDateTime.of(2026, 10, 31, 12, 0), SliceInfo.unknown());

    // A store that holds exactly one file and has never heard of any other id.
    private final GcodeFileService files = new GcodeFileService() {
        @Override
        public Publisher<GcodeFile> store(String name, Publisher<ByteBuffer> content) {
            return Mono.just(held);
        }

        @Override
        public Optional<GcodeFile> fetch(UUID id) {
            return held.id().equals(id) ? Optional.of(held) : Optional.empty();
        }

        @Override
        public Stream<GcodeFile> fetchAll() {
            return Stream.of(held);
        }

        @Override
        public Publisher<ByteBuffer> read(UUID id) {
            return Flux.empty();
        }

        @Override
        public void delete(UUID id) {
        }
    };

    private final WebTestClient client =
            WebTestClient.bindToController(new GcodeFileController(files)).build();

    @Test
    void aFileTheFarmDoesNotHoldIsNotFound() {
        client.get().uri("/api-files/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().isEmpty();
    }

    @Test
    void aFileTheFarmHoldsIsReturned() {
        client.get().uri("/api-files/{id}", held.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(GcodeFile.class).isEqualTo(held);
    }
}
