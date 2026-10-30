package org.qw3rtrun.p3d.g.marlin.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.g.protocol.GRs

/**
 * Cover for the SD file lifecycle - what the card says as a file is written, listed, opened and
 * finished.
 *
 * **Every literal here is a captured line, not a read of the wiki.** `SdWriteProbe` was run against
 * MarlinSim (bugfix-2.1.x) and the transcript it printed is the fixture: `M28` then four numbered
 * content lines, `M29`, `M20`, `M32`, the print, then `M21`. That matters more than usual for this
 * family, because the firmware's wording is not uniform - `Done saving file.` carries a full stop
 * and `Done printing file` does not, and no documentation of either says so.
 */
class SdCardRsTest {

    /**
     * The session as the probe recorded it, in order, with the type each line decodes to.
     *
     * The `ok`s and the auto-reports between them are dropped; what is left is every line in that
     * transcript that is about the card.
     */
    private val session: List<Pair<String, GRs<*>>> = listOf(
        "echo:Now fresh file: test.gco" to SdFreshFile("test.gco"),
        "Writing to file: test.gco" to SdWritingToFile("test.gco"),
        "Done saving file." to SdDoneSaving,
        "Begin file list" to SdFileListBegin,
        "End file list" to SdFileListEnd,
        "File opened: test.gco Size: 47" to SdFileOpened("test.gco", 47),
        "File selected" to SdFileSelected,
        "Done printing file" to SdDonePrinting,
        "echo:SD card ok" to SdCardOk,
        "Not SD printing" to SdNotPrinting,
        "Current file: (no file)" to SdCurrentFile(null),
        "SD printing byte 1234/56789" to SdPrinting(1234, 56789),
    )

    @Test
    fun `every line of the captured session decodes through the registry`() {
        for ((line, expected) in session) {
            assertEquals(expected, MarlinRsDecoder.decode(line)) { "failed for: $line" }
        }
    }

    @Test
    fun `every one of them round-trips`() {
        for ((line, expected) in session) {
            assertEquals(line, expected.encode()) { "encode differs for: $line" }
            assertEquals(expected, MarlinRsDecoder.decode(expected.encode()))
        }
    }

    @Test
    fun `all of them are one family a host can match in one place`() {
        // The reason SdCardRs exists. A Storage view subscribes to this and is told about the card
        // without enumerating a dozen classes - and without missing the thirteenth.
        for ((line, _) in session) {
            assertTrue(MarlinRsDecoder.decode(line) is SdCardRs<*>) { "not an SdCardRs: $line" }
        }
    }

    @Test
    fun `no line of the session is claimed by two decoders`() {
        for ((line, _) in session) {
            val claimants = MarlinRsDecoder.decoders.filter { it.match(line) }
            assertEquals(1, claimants.size) { "${claimants.size} decoders claim: $line" }
        }
    }

    @Test
    fun `the two echo-carried ones are both an SdCardRs and an EchoRs`() {
        // The same both-at-once shape BusyNotice has: what they report is the card, how they arrive
        // is the echo channel, and neither view has to know the other exists.
        for (line in listOf("echo:SD card ok", "echo:Now fresh file: test.gco")) {
            val reply = MarlinRsDecoder.decode(line)
            assertTrue(reply is SdCardRs<*>) { "not an SdCardRs: $line" }
            assertTrue(reply is EchoRs<*>) { "not an EchoRs: $line" }
        }
    }

    @Test
    fun `the two echo-carried ones stop being undifferentiated prose`() {
        // They used to land as EchoMessage, which meant a host had to compare against the text by
        // hand to learn whether there was a card at all.
        assertNull(EchoMessage.decode("echo:SD card ok"))
        assertNull(EchoMessage.decode("echo:Now fresh file: test.gco"))
        assertEquals(SdCardOk, EchoRsDecoder.decode("echo:SD card ok"))
        assertEquals(SdFreshFile("test.gco"), EchoRsDecoder.decode("echo:Now fresh file: test.gco"))

        // and ordinary prose on the same channel is still ordinary prose
        assertEquals(EchoMessage("Print time: 0s"), EchoRsDecoder.decode("echo:Print time: 0s"))
    }

    @Test
    fun `the echo-carried ones are not reached through the card decoder`() {
        // EchoRsDecoder owns the `echo:` prefix and partitions it. Claiming them in both places
        // would give the registry two claimants for one line.
        assertNull(SdCardRsDecoder.decode("echo:SD card ok"))
        assertNull(SdCardRsDecoder.decode("echo:Now fresh file: test.gco"))
    }

    @Test
    fun `the two Done lines are told apart by the full stop the firmware writes`() {
        // Not a tidying opportunity: the two messages reach the line through different calls in the
        // firmware, and a type that re-encoded to a line its printer does not write would be worse
        // than the inconsistency.
        assertEquals(SdDoneSaving, MarlinRsDecoder.decode("Done saving file."))
        assertEquals(SdDonePrinting, MarlinRsDecoder.decode("Done printing file"))
        assertNull(SdDoneSaving.decode("Done saving file"))
        assertNull(SdDonePrinting.decode("Done printing file."))
    }

    @Test
    fun `the opened size is the payloads and their terminators, with no N or checksum`() {
        // 47 is what the probe measured for `G28` / `M117 Created` / `M118 Done` /
        // `M118 P0 Done-P0` - 4 + 13 + 10 + 16 characters plus a CRLF each. The lines went out
        // numbered and checksummed; the firmware strips both before writing.
        val opened = SdFileOpened.decode("File opened: test.gco Size: 47")!!
        assertEquals(47, opened.size)
        assertEquals("test.gco", opened.file)
        assertEquals(
            "G28\r\nM117 Created\r\nM118 Done\r\nM118 P0 Done-P0\r\n".length.toLong(),
            opened.size,
        )
    }

    @Test
    fun `a file larger than Int decodes`() {
        assertEquals(
            SdFileOpened("BENCHY.GCO", 3_000_000_000L),
            SdFileOpened.decode("File opened: BENCHY.GCO Size: 3000000000"),
        )
    }

    @Test
    fun `a size too wide for a Long is absence rather than a throw`() {
        // spec 9: malformed input off the wire is a value, never a crash.
        assertNull(SdFileOpened.decode("File opened: x.gco Size: 99999999999999999999"))
    }

    @Test
    fun `a filename with blanks survives, because the size marker is the fixed point`() {
        // LONG_FILENAME_HOST_SUPPORT writes names like this. The name is read back from ` Size: `,
        // not forward from the first blank.
        assertEquals(
            SdFileOpened("my little benchy.gcode", 2847561),
            SdFileOpened.decode("File opened: my little benchy.gcode Size: 2847561"),
        )
        assertEquals(
            SdWritingToFile("my little benchy.gcode"),
            SdWritingToFile.decode("Writing to file: my little benchy.gcode"),
        )
        assertEquals(
            SdFreshFile("my little benchy.gcode"),
            SdFreshFile.decode("echo:Now fresh file: my little benchy.gcode"),
        )
    }

    @Test
    fun `a filename that itself contains the size marker keeps all of it`() {
        // The scan takes the **last** ` Size: `, which is the only reading that cannot truncate a
        // real name.
        assertEquals(
            SdFileOpened("my Size: 1.gco", 47),
            SdFileOpened.decode("File opened: my Size: 1.gco Size: 47"),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "File opened: test.gco",
            "File opened: Size: 47",
            "File opened: test.gco Size:",
            "File opened: test.gco Size: x",
            "File opened: test.gco Size: 47 extra",
            "File opened:",
        ]
    )
    fun `a malformed File opened line decodes to absence`(line: String) {
        assertNull(SdFileOpened.decode(line), "should not decode: $line")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Writing to file:", "Writing to file:   ", "Writing to file", "Current file: x"])
    fun `a malformed Writing to file line decodes to absence`(line: String) {
        assertNull(SdWritingToFile.decode(line), "should not decode: $line")
    }

    @ParameterizedTest
    @ValueSource(strings = ["echo:Now fresh file:", "echo:Now fresh file", "Now fresh file: x", "echo:SD card"])
    fun `a malformed fresh-file line decodes to absence`(line: String) {
        assertNull(SdFreshFile.decode(line), "should not decode: $line")
    }

    @ParameterizedTest
    @ValueSource(strings = ["File select", "File selected now", "Done saving", "Done printing", "", "   "])
    fun `a line that is none of these decodes to absence`(line: String) {
        assertNull(SdCardRsDecoder.decode(line), "should not decode: $line")
    }

    @Test
    fun `the keyword lines fold and tolerate surrounding blanks, as every other keyword reply does`() {
        assertEquals(SdFileSelected, SdFileSelected.decode("  FILE SELECTED\r\n"))
        assertEquals(SdDoneSaving, SdDoneSaving.decode("done saving file."))
        assertEquals(SdDonePrinting, SdDonePrinting.decode("DONE PRINTING FILE"))
        assertEquals(SdCardOk, SdCardOk.decode("ECHO:SD CARD OK"))
    }

    @Test
    fun `M27's answers are part of the family too`() {
        // Purely additive: SdStatusRs now extends SdCardRs, so a host watching storage is told
        // about print progress on the card without a second subscription.
        for (line in listOf("Not SD printing", "SD printing byte 1/2", "Current file: (no file)")) {
            assertTrue(SdStatusRsDecoder.decode(line) is SdCardRs<*>) { "not an SdCardRs: $line" }
            assertNotNull(SdCardRsDecoder.decode(line)) { "the card decoder missed: $line" }
        }
    }
}
