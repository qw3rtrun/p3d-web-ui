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
 * The farm's own G-code store - issue #43's feature 6, through {@code /api-files}.
 *
 * <p><strong>It is the one feature here with no machine in it.</strong> A stored file is the
 * farm's, not a printer's: it is uploaded once and can be sent to any number of machines, or to
 * none. So there is no {@code MockPrinter} below and no configuration matrix, which is the rule in
 * §5 of the integration-test spec applied rather than suspended - a feature is driven over every
 * configuration where the answer should differ, and nothing about a printer changes any answer
 * here. The second observable, what reached the machine, simply has no machine to reach.
 *
 * <p>What is left is the first observable, and there is a lot of it, because this endpoint carries
 * more browser-visible contract than any other in the application:
 *
 * <ul>
 *   <li><strong>the content is addressed by its hash</strong>, so the same bytes stored twice are
 *       one file. {@code GcodeFileServiceTest} proves that of the service; what is proved here is
 *       that a second HTTP upload answers with the first record rather than making a second;</li>
 *   <li><strong>what the slicer said reaches {@code slice}</strong>, in both dialects and from both
 *       ends of the file - and a file with no slicer comments is a fact, not an error;</li>
 *   <li><strong>the download carries {@code Content-Disposition} and {@code Content-Length}</strong>,
 *       because a browser names the saved file by the first and draws a progress bar from the
 *       second;</li>
 *   <li><strong>nothing on the path turns the content into a {@code byte[]}.</strong> That is
 *       enforced by signature in {@code api} - {@code GcodeFileService} takes and returns a
 *       {@code Publisher<ByteBuffer>} - and measured in {@code GcodeBlobStoreTest}, which asserts
 *       how many chunks are ever in flight. Neither fact is visible from out here. What this level
 *       can honestly say is that a file too large for one socket read goes in and comes back byte
 *       for byte, which is the thing a user would notice if any of it were wrong.</li>
 * </ul>
 *
 * <p>Every fixture carries its own mark, so no two tests here upload the same bytes by accident.
 * See {@link GcodeFixture} - the store would quite correctly hand the second one the first one's
 * record, and the test would be reading another test's file.
 */
class GcodeFileIntegrationTest extends FarmIntegrationTest {

    /**
     * An upload answers with the record it became: its identity, its size, its line count.
     *
     * <p>The hash is computed by the fixture rather than read out of the response and compared to
     * itself. It is the one field a browser cannot check and the one the rest of the farm leans
     * on - a job holds a file id for hours and has to be able to say which bytes it meant.
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
     * What the slicer wrote reaches the browser, in Cura's dialect.
     *
     * <p>The numbers are the ones {@code SliceInfoReaderTest} measures in {@code :backend:core}, so
     * this is not re-deciding what {@code 1.2345m} means - it is asserting that the reading survived
     * being stored, read back out of the row and serialised. The field is a nested object and the
     * names below are what a browser would switch on.
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
     * A file nothing could be read from is stored anyway, and says so rather than lying.
     *
     * <p>Hand-written G-code is a perfectly printable file. The record carries a {@code slice}
     * whose every field is null, which is the honest answer; a zero would read as a print that
     * takes no time and uses no filament.
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
     * A summary written at the <em>end</em> of a large file is still read.
     *
     * <p>PrusaSlicer names itself at the top and repeats its configuration at the bottom, and the
     * store reads bounded windows at each end rather than the whole file - so on a file this size
     * the slicer's name and its numbers come from two different reads with 192 KiB of motion
     * between them. A reader that only looked at the top would answer with the name and three
     * nulls, which is the least useful subset and would pass any test that used a small file.
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
     * A file larger than one socket read goes in and comes back byte for byte.
     *
     * <p>This is what this level can say about the rule that nothing on the path holds the content.
     * The real enforcement is a signature in {@code api} and a chunk count in
     * {@code GcodeBlobStoreTest}, neither visible from here; what is visible is that a file that
     * cannot arrive in one buffer survives the round trip intact - which is exactly what would stop
     * being true if some later change read the body into an array and truncated, re-ordered or
     * re-encoded it.
     *
     * <p>Both ends are checked: the hash on the record proves the server received these bytes, and
     * the download proves it can give them back.
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
     * The download carries what a browser saves by.
     *
     * <p>{@code Content-Disposition} is how the file gets its name back - without it the browser
     * saves it as the id - and {@code Content-Length} is the only thing that lets a client show
     * progress without the server buffering the file to find out how big it is. Both are easy to
     * lose: a change that returned the {@code Flux} directly instead of a {@code ResponseEntity}
     * would still answer 200 with the right bytes and silently drop both.
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
     * The same bytes uploaded twice are one file, under the name they arrived with first.
     *
     * <p>Two fixtures with the same mark and different names: identical content, different
     * {@code X-File-Name}. The second upload answers with the first record - same id, same name -
     * and the farm holds one file rather than two.
     *
     * <p>That the second name is <em>lost</em> is the deliberate part. A job holds a file id, and
     * two ids over identical bytes would mean a print that outlived a restart had to guess which
     * copy it was reading.
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
