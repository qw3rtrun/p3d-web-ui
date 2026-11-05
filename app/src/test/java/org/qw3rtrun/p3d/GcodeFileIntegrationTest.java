package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The farm's own G-code store - issue #43 feature 6, {@code /api-files}. The one feature here with
 * no machine in it: a stored file is the farm's, uploaded once and sent to any number of printers
 * or none, so there is no {@code MockPrinter} and no configuration matrix - §5 applied rather than
 * suspended, since nothing about a printer changes any answer here.
 *
 * <p>What this endpoint carries, and nowhere else does as much of: content addressed by its hash,
 * so a second upload of the same bytes answers with the first record ({@code GcodeFileServiceTest}
 * proves this of the service, this proves it of the HTTP layer); what the slicer said, in both
 * dialects and from both ends of the file, with no comments being a fact rather than an error;
 * {@code Content-Disposition} and {@code Content-Length} on download, for the filename and the
 * progress bar; and that nothing on the path turns the content into a {@code byte[]} - enforced by
 * {@code GcodeFileService}'s {@code Publisher<ByteBuffer>} signature and measured in
 * {@code GcodeBlobStoreTest}, neither visible here, where what can be said is that an oversized
 * file round-trips byte for byte.
 *
 * <p>Every fixture carries its own mark (see {@link GcodeFixture}) so no two tests upload the same
 * bytes and collide on the dedup the first bullet above tests.
 */
class GcodeFileIntegrationTest extends FarmIntegrationTest {

    /**
     * The hash is computed by the fixture rather than read out of the response and compared to
     * itself - it is the one field a browser cannot check, and the one a job leans on for hours to
     * say which bytes it meant.
     */
    @Test
    void anUploadComesBackAsTheRecordItBecame() {
        var file = GcodeFixture.cura("recorded").named("benchy.gcode");

        var stored = upload(file);

        assertNotNull(idOf(stored));
        assertEquals("benchy.gcode", stored.get("name"));
        assertEquals(file.sha256(), stored.get("contentHash"), "SHA-256 of the bytes, lower-case hex");
        assertEquals(file.sizeBytes(), number(stored.get("sizeBytes")));
        assertEquals(file.lineCount(), number(stored.get("lineCount")));

        var uploadedAt = stored.get("uploadedAt");
        assertInstanceOfString(uploadedAt, "uploadedAt");
        assertNotNull(LocalDateTime.parse((String) uploadedAt),
                "the browser reads uploadedAt as a string and renders it as a date");
    }

    /**
     * The numbers are the ones {@code SliceInfoReaderTest} measures in {@code :backend:core} - not
     * re-deciding what {@code 1.2345m} means, just that the reading survives storage, the row and
     * serialisation.
     */
    @Test
    void whatTheSlicerSaidReachesTheBrowser() {
        var file = GcodeFixture.cura("sliced").named("sliced.gcode");

        var slice = sliceOf(upload(file));

        assertEquals("Cura_SteamEngine 5.2.1", slice.get("slicer"));
        assertEquals(3661L, number(slice.get("estimatedSeconds")));
        assertEquals(1234.5, decimal(slice.get("filamentMillimetres")),
                "Cura writes 1.2345m and the record keeps millimetres");
        assertEquals(0.2, decimal(slice.get("layerHeight")));
    }

    /**
     * Hand-written G-code is a perfectly printable file; the record's {@code slice} has every field
     * null rather than zero, since zero would read as a print that takes no time and uses no
     * filament.
     */
    @Test
    void aFileWithNoSlicerCommentsIsStoredAnyway() {
        var file = GcodeFixture.unsliced("plain").named("handwritten.gcode").withBodyLines(2);

        var stored = upload(file);

        assertEquals(file.lineCount(), number(stored.get("lineCount")));
        assertEquals(8L, number(stored.get("lineCount")), "G21, G90, G28, the mark, two moves, M104, M84");

        var slice = sliceOf(stored);
        assertNull(slice.get("slicer"));
        assertNull(slice.get("estimatedSeconds"));
        assertNull(slice.get("filamentMillimetres"));
        assertNull(slice.get("layerHeight"));
    }

    /**
     * PrusaSlicer names itself at the top and repeats its configuration at the bottom; the store
     * reads bounded windows at each end, so here the name and the numbers come from two different
     * reads with 192 KiB of motion between them - a top-only reader would pass on a small file but
     * answer with the name and three nulls here.
     */
    @Test
    void aSummaryAtTheEndOfALargeFileIsStillRead() {
        var file = GcodeFixture.prusaSlicer("tail")
                .named("prusa-big.gcode")
                .ofAtLeast(GcodeFixture.BIGGER_THAN_ONE_BUFFER);

        var stored = upload(file);
        var slice = sliceOf(stored);

        assertTrue(number(stored.get("sizeBytes")) >= GcodeFixture.BIGGER_THAN_ONE_BUFFER,
                "the point of this test is a file whose two ends are nowhere near each other");
        assertEquals("PrusaSlicer 2.6.0+win64", slice.get("slicer"), "read from the head");
        assertEquals(3723L, number(slice.get("estimatedSeconds")), "1h 2m 3s, read from the tail");
        assertEquals(1234.5, decimal(slice.get("filamentMillimetres")), "read from the tail");
        assertEquals(0.2, decimal(slice.get("layerHeight")), "read from the tail");
    }

    /**
     * Both ends are checked: the hash on the record proves the server received these bytes, and the
     * download proves it can give them back - what would fail if a later change read the body into
     * an array and truncated, re-ordered or re-encoded it.
     */
    @Test
    void aFileLargerThanOneBufferRoundTripsByteForByte() {
        var file = GcodeFixture.cura("roundtrip")
                .named("large.gcode")
                .ofAtLeast(GcodeFixture.BIGGER_THAN_ONE_BUFFER);

        var stored = upload(file);
        assertEquals(file.sha256(), stored.get("contentHash"), "every byte sent was hashed");

        var downloaded = download(idOf(stored));

        assertArrayEquals(file.bytes(), downloaded,
                "the file came back changed, which is what a buffer being flattened looks like");
    }

    /**
     * {@code Content-Disposition} is how the file gets its name back (without it the browser saves
     * it as the id); {@code Content-Length} lets a client show progress without the server buffering
     * to find out how big the file is. Both are easy to lose: returning the {@code Flux} directly
     * instead of a {@code ResponseEntity} would still answer 200 with the right bytes and silently
     * drop both.
     */
    @Test
    void theDownloadCarriesWhatABrowserSavesBy() {
        var file = GcodeFixture.cura("headers").named("saved-as-this.gcode");
        var stored = upload(file);

        farm.get().uri("/api-files/{id}/content", idOf(stored))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectHeader().contentLength(file.sizeBytes())
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"saved-as-this.gcode\"");
    }

    /**
     * Two fixtures, same mark, different names: the second upload answers with the first record, and
     * losing the second name is deliberate - a job holds a file id, and two ids over identical bytes
     * would mean a print outliving a restart had to guess which copy it meant.
     */
    @Test
    void theSameBytesUploadedTwiceAreOneFile() {
        var first = upload(GcodeFixture.cura("deduped").named("benchy.gcode"));
        var second = upload(GcodeFixture.cura("deduped").named("benchy-final.gcode"));

        assertEquals(idOf(first), idOf(second));
        assertEquals("benchy.gcode", second.get("name"), "the name it was first stored under");
        assertEquals(first.get("contentHash"), second.get("contentHash"));

        var hash = (String) first.get("contentHash");
        assertEquals(1, storedFiles().stream().filter(f -> hash.equals(f.get("contentHash"))).count(),
                "one blob, one row: " + storedFiles());
    }

    /**
     * Different bytes are different files, and the listing shows them both.
     *
     * <p>The listing is also checked against the single-file endpoint, because the browser uses
     * one to build a picker and the other to fill a panel, and a field present in one and missing
     * from the other is a panel that renders blank for no visible reason. Both of those are read
     * back out of the database, so they are compared to each other and not to the upload's own
     * answer - that one still carries the timestamp at the precision the JVM made it, before the
     * column rounded it.
     */
    @Test
    void theListingCarriesEveryFileTheFarmHolds() {
        var one = idOf(upload(GcodeFixture.cura("listing-one").named("one.gcode")));
        var two = idOf(upload(GcodeFixture.unsliced("listing-two").named("two.gcode")));

        var listing = storedFiles();

        assertEquals(List.of("two.gcode", "one.gcode"),
                listing.stream().map(f -> (String) f.get("name")).toList(),
                "every file the farm holds, newest first: " + listing);
        assertEquals(listed(listing, one), recordOf(one), "the listing and the single file must agree");
        assertEquals(listed(listing, two), recordOf(two));
    }

    /**
     * A deleted file is gone from the listing and its bytes are no longer served.
     *
     * <p>Asserted at the endpoints rather than by looking at the directory. "It was deleted" can
     * only honestly mean "the farm no longer offers it", and the download is where a job would find
     * out the hard way.
     */
    @Test
    void aDeletedFileIsGoneFromTheFarm() {
        var stored = upload(GcodeFixture.cura("doomed").named("doomed.gcode"));
        var id = idOf(stored);

        farm.delete().uri("/api-files/{id}", id).exchange().expectStatus().isOk();

        assertEquals(0, storedFiles().stream().filter(f -> id.equals(idOf(f))).count(),
                "the listing still offers a file that was deleted: " + storedFiles());
        farm.get().uri("/api-files/{id}", id).exchange().expectStatus().isNotFound();
        farm.get().uri("/api-files/{id}/content", id).exchange().expectStatus().isNotFound();
    }

    /**
     * A file the farm does not hold is not found, at both endpoints that could answer for it.
     *
     * <p>Both, because the browser's client parses the body of one as JSON and streams the other to
     * a download. A 200 with an empty body is the failure mode that looks fine in a log and throws
     * a syntax error in the browser.
     */
    @Test
    void aFileTheFarmDoesNotHoldIsNotFound() {
        var unknown = UUID.randomUUID();

        farm.get().uri("/api-files/{id}", unknown).exchange().expectStatus().isNotFound();
        farm.get().uri("/api-files/{id}/content", unknown).exchange().expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------- reading a record

    private byte[] download(UUID id) {
        return farm.get().uri("/api-files/{id}/content", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
    }

    private static Map<String, Object> listed(List<Map<String, Object>> listing, UUID id) {
        return listing.stream().filter(f -> id.equals(idOf(f))).findFirst()
                .orElseThrow(() -> new AssertionError("the listing does not carry " + id + ": " + listing));
    }

    private Map<String, Object> recordOf(UUID id) {
        return json(farm.get().uri("/api-files/{id}", id).exchange().expectStatus().isOk());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sliceOf(Map<String, Object> record) {
        var slice = record.get("slice");
        assertNotNull(slice, "slice is never null - a file nothing was read from carries an empty one");
        return (Map<String, Object>) slice;
    }

    private static double decimal(Object value) {
        assertNotNull(value, "expected a number");
        return ((Number) value).doubleValue();
    }

    private static void assertInstanceOfString(Object value, String field) {
        assertTrue(value instanceof String, field + " must reach the browser as a string, not " + value);
    }
}
