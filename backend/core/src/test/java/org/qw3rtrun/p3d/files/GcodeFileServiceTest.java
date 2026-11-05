package org.qw3rtrun.p3d.files;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.files.service.GcodeBlobStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store and the row, agreeing - against the real Liquibase schema, since an entity and a
 * changeset disagreeing is a production-only failure that has already happened once over a reserved
 * word. Boots this module alone, against {@code db/changelog/gcode-files.xml} rather than the
 * application-wide changelog {@code :app} assembles.
 */
@SpringBootTest
class GcodeFileServiceTest {

    // every test uses distinct content on purpose: tests share one context and database, and the
    // store deduplicates by content, so identical bytes would find each other's rows.

    @SpringBootApplication
    static class TestApp {
    }

    // not @TempDir: the store is a Spring bean built when the context starts, so the directory must
    // exist before JUnit would hand one over.
    @DynamicPropertySource
    static void blobRoot(DynamicPropertyRegistry registry) throws IOException {
        var root = Files.createTempDirectory("gcode-blobs-");
        root.toFile().deleteOnExit();
        registry.add("p3d.files.root", root::toString);
    }

    @Autowired
    private GcodeFileService files;

    private static ByteBuffer chunk(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private GcodeFile store(String name, String content) {
        return Mono.from(files.store(name, Flux.just(chunk(content)))).block();
    }

    private static String contentOf(String marker) {
        return """
                ;FLAVOR:Marlin
                ;TIME:3661
                ;Filament used: 1.2345m
                ;Layer height: 0.2
                ;Generated with Cura_SteamEngine 5.2.1
                G28
                G1 X%s
                """.formatted(marker);
    }

    @Test
    void anUploadIsRecordedWithWhatWasCountedAndWhatTheSlicerSaid() {
        var stored = store("benchy.gcode", contentOf("10"));

        assertNotNull(stored.id());
        assertEquals("benchy.gcode", stored.name());
        assertEquals(64, stored.contentHash().length(), "SHA-256 as lower-case hex");
        assertEquals(contentOf("10").length(), stored.sizeBytes());
        assertEquals(7, stored.lineCount());
        assertEquals("Cura_SteamEngine 5.2.1", stored.slice().slicer());
        assertEquals(3661L, stored.slice().estimatedSeconds());
        assertEquals(0, new BigDecimal("0.2").compareTo(stored.slice().layerHeight()));
    }

    // deliberate: a job holds a file id, and two ids on identical bytes would mean recovery had to
    // guess which copy a print was reading.
    @Test
    void theSameBytesUploadedTwiceAreOneFile() {
        var first = store("benchy.gcode", contentOf("11"));
        var second = store("benchy-final.gcode", contentOf("11"));

        assertEquals(first.id(), second.id());
        assertEquals("benchy.gcode", second.name(), "the name it was first stored under");
        assertEquals(1, files.fetchAll().filter(f -> f.id().equals(first.id())).count());
    }

    @Test
    void differentBytesAreDifferentFiles() {
        var first = store("a.gcode", contentOf("12"));
        var second = store("b.gcode", contentOf("13"));

        assertFalse(first.id().equals(second.id()));
        assertFalse(first.contentHash().equals(second.contentHash()));
    }

    @Test
    void whatWasUploadedIsWhatIsReadBack() {
        var content = contentOf("30");
        var stored = store("readback.gcode", content);

        var read = Flux.from(files.read(stored.id()))
                .map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString())
                .reduce("", String::concat)
                .block();

        assertEquals(content, read);
    }

    @Test
    void anUnknownIdReadsAsEmptyRatherThanFailing() {
        assertTrue(files.fetch(UUID.randomUUID()).isEmpty());
        assertEquals(0, Flux.from(files.read(UUID.randomUUID())).count().block());
    }

    @Test
    void aStoredFileCanBeFetchedById() {
        var stored = store("fetchable.gcode", contentOf("40"));

        var fetched = files.fetch(stored.id()).orElse(null);

        assertNotNull(fetched);
        assertEquals(stored.contentHash(), fetched.contentHash());
    }

    /** Deleting forgets the row and the bytes, since nothing else refers to them. */
    @Test
    void deletingRemovesTheRowAndTheContent() {
        var stored = store("doomed.gcode", contentOf("50"));

        files.delete(stored.id());

        assertTrue(files.fetch(stored.id()).isEmpty());
        assertEquals(0, Flux.from(files.read(stored.id())).count().block(),
                "and the bytes went with it");
    }

    /** A file nothing could be read from is still stored, because it is still printable. */
    @Test
    void aFileWithNoSlicerHeaderIsStoredAnyway() {
        var stored = store("handwritten.gcode", "G28\nG1 X10\n");

        assertEquals(2, stored.lineCount());
        assertFalse(stored.slice().isKnown());
    }

    @Test
    void theListingIsNewestFirst() {
        var first = store("first.gcode", contentOf("60"));
        var second = store("second.gcode", contentOf("70"));

        try (Stream<GcodeFile> all = files.fetchAll()) {
            var ids = all.map(GcodeFile::id).toList();
            assertTrue(ids.indexOf(second.id()) <= ids.indexOf(first.id()),
                    "the most recently uploaded file comes first");
        }
    }

    // a row pointing at content the store does not hold would be a file that lists fine and fails
    // when a print starts.
    @Test
    void theRowAndTheBlobAgree(@Autowired GcodeBlobStore blobs) {
        var stored = store("addressed.gcode", contentOf("80"));

        assertTrue(blobs.holds(stored.contentHash()));
    }
}
