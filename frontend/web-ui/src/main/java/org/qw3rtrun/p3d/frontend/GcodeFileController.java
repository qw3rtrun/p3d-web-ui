package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Upload, download and list stored G-code.
 *
 * <p><strong>Nothing here reads a file into memory, and the shape of every method is what says
 * so.</strong> The request body arrives as a {@code Flux<DataBuffer>} and leaves as one; neither
 * direction ever holds more than a chunk. A {@code @RequestBody byte[]} or a {@code String} would
 * be the obvious spelling and would put a two-hundred-megabyte array on the heap per concurrent
 * upload, which is plan item C1's "watch" in one line of code.
 *
 * <p><strong>The buffer conversion is this layer's job, and it is where the pooled buffer's
 * lifetime ends.</strong> {@code api} may not name a Spring type - it is the vocabulary every
 * submodel is written in - so the store takes {@code ByteBuffer}. WebFlux hands out pooled,
 * reference-counted {@code DataBuffer}s that must be released, and this is the only place that
 * knows both facts, so this is where the copy and the release happen. One copy per chunk, chunks
 * being tens of kilobytes.
 */
@Slf4j
@RestController
@RequestMapping("/api-files")
@RequiredArgsConstructor
public class GcodeFileController {

    private static final DataBufferFactory BUFFERS = new DefaultDataBufferFactory();

    private final GcodeFileService files;

    // The name is a header, not a multipart form: multipart would mean the framework deciding where
    // to put the parts, a temporary file or the heap for something this size. A raw body keeps the
    // bytes under this method's control the whole way.
    @PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<GcodeFile> upload(@RequestHeader(value = "X-File-Name", defaultValue = "upload.gcode") String name,
                                  @RequestBody Flux<DataBuffer> body) {
        return Mono.from(files.store(name, body.map(GcodeFileController::toByteBuffer)));
    }

    // Copied, not wrapped: a view would hand the store a window onto memory this method is about to
    // release back to the pool, correct only while the store consumes it synchronously - a contract
    // the signature cannot state.
    private static ByteBuffer toByteBuffer(DataBuffer buffer) {
        try {
            var copy = ByteBuffer.allocate(buffer.readableByteCount());
            buffer.toByteBuffer(copy);
            return copy;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<GcodeFile> list() {
        return offLoop(() -> files.fetchAll().toList()).flatMapIterable(f -> f);
    }

    // The blocking boundary, in one place - the same shape FDMPrinterManagementController uses.
    private <T> Mono<T> offLoop(Supplier<T> blocking) {
        return Mono.fromSupplier(blocking).subscribeOn(Schedulers.boundedElastic());
    }

    // See Outcomes#found for why an empty Mono cannot be returned here directly.
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<GcodeFile>> fetch(@PathVariable UUID id) {
        return offLoop(() -> files.fetch(id)).map(Outcomes::found);
    }

    // APPLICATION_OCTET_STREAM so a browser saves rather than renders it; content length comes from
    // the row, which is what lets a client show a progress bar without the server buffering first.
    @GetMapping("/{id}/content")
    public Mono<ResponseEntity<Flux<DataBuffer>>> download(@PathVariable UUID id) {
        return offLoop(() -> files.fetch(id))
                .flatMap(Mono::justOrEmpty)
                .map(file -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(file.sizeBytes())
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + file.name().replace("\"", "") + "\"")
                        .body(Flux.from(files.read(id)).map(BUFFERS::wrap)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable UUID id) {
        return Mono.fromRunnable(() -> files.delete(id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
