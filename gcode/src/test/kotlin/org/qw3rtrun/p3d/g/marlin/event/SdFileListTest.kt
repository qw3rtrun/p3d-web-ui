package org.qw3rtrun.p3d.g.marlin.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Cover for `M20`'s listing - the module's first multi-line reply.
 *
 * Both captured shapes are here as fixtures rather than as invented ones: the **empty** listing is
 * from `marlin-replies.txt`, which `ReplyCorpusProbe` took off MarlinSim with a bare card, and the
 * **non-empty** one is what `SdWriteProbe` saw after writing a file through `M28`/`M29`. That probe
 * is also where `TEST.GCO 47` comes from - uppercase 8.3, and a size that is the payloads plus a
 * CRLF each, with the `N` words and checksums stripped.
 *
 * The claims: the two literals decode, an entry does **not** decode on its own, the reader
 * assembles only on a terminator, an interrupted listing yields nothing rather than a short one,
 * and the block round-trips through [SdFileListReader] rather than through a `GRsDecoder`.
 */
class SdFileListTest {

    private fun read(vararg lines: String): SdFileList? {
        val reader = SdFileListReader()
        var last: SdFileList? = null
        for (line in lines) {
            val list = reader.read(line)
            if (list != null) last = list
        }
        return last
    }

    @Test
    fun `the two literals decode, through the registry as well as on their own`() {
        assertEquals(SdFileListBegin, SdFileListBegin.decode("Begin file list"))
        assertEquals(SdFileListEnd, SdFileListEnd.decode("End file list"))
        assertEquals(SdFileListBegin, MarlinRsDecoder.decode("Begin file list"))
        assertEquals(SdFileListEnd, MarlinRsDecoder.decode("End file list"))
    }

    @Test
    fun `both literals round-trip`() {
        assertEquals("Begin file list", SdFileListBegin.encode())
        assertEquals("End file list", SdFileListEnd.encode())
        assertEquals(SdFileListBegin, MarlinRsDecoder.decode(SdFileListBegin.encode()))
        assertEquals(SdFileListEnd, MarlinRsDecoder.decode(SdFileListEnd.encode()))
    }

    @Test
    fun `an entry is not a reply the registry claims`() {
        // The design decision this file exists to pin. `TEST.GCO 47` is `<word> <number>`, and a
        // decoder for that shape would start claiming lines from every other command - the same
        // objection EndstopStateRs records about `<word>: <word>`. Nothing reads an entry except
        // something that already knows a listing is open.
        assertNull(MarlinRsDecoder.decode("TEST.GCO 47"))
        assertNull(SdCardRsDecoder.decode("TEST.GCO 47"))
    }

    @Test
    fun `the captured non-empty listing assembles`() {
        // SdWriteProbe, after M28 test gco / three content lines / M29.
        assertEquals(
            SdFileList(listOf(SdFile("TEST.GCO", 47))),
            read("Begin file list", "TEST.GCO 47", "End file list"),
        )
    }

    @Test
    fun `the captured empty listing assembles, and is not absence`() {
        // marlin-replies.txt: a card with nothing printable on it answers with the two markers and
        // nothing between them. An empty list is the answer - the card holds no files - and that is
        // a different fact from "no listing arrived", which is what null means here.
        assertEquals(SdFileList(emptyList()), read("Begin file list", "End file list"))
    }

    @Test
    fun `several entries keep the order the card listed them in`() {
        val list = read(
            "Begin file list",
            "TEST.GCO 47",
            "BENCHY.GCO 2847561",
            "CALIB~1.GCO 1024",
            "End file list",
        )!!
        assertEquals(
            listOf(
                SdFile("TEST.GCO", 47),
                SdFile("BENCHY.GCO", 2847561),
                SdFile("CALIB~1.GCO", 1024),
            ),
            list.files,
        )
    }

    @Test
    fun `nothing is produced until the terminator arrives`() {
        val reader = SdFileListReader()
        assertNull(reader.read("Begin file list"))
        assertTrue(reader.isReading())
        assertNull(reader.read("TEST.GCO 47"))
        assertTrue(reader.isReading())

        assertEquals(SdFileList(listOf(SdFile("TEST.GCO", 47))), reader.read("End file list"))
        assertFalse(reader.isReading())
    }

    @Test
    fun `a line outside a listing is ignored`() {
        val reader = SdFileListReader()
        assertNull(reader.read("ok"))
        assertNull(reader.read("TEST.GCO 47"))
        assertNull(reader.read("End file list"))
        assertFalse(reader.isReading())
    }

    @Test
    fun `an interrupted listing yields nothing rather than a short one`() {
        // The claim that makes a whole-block value worth having. A host cannot tell a card with one
        // file from a card whose listing was cut off after one, so a listing something interrupted
        // is reported as no answer at all - not as the entries that did arrive.
        assertNull(read("Begin file list", "TEST.GCO 47", "ok", "BENCHY.GCO 12", "End file list"))
    }

    @Test
    fun `the reader recovers after an interrupted listing`() {
        val reader = SdFileListReader()
        reader.read("Begin file list")
        reader.read("TEST.GCO 47")
        reader.read("echo:busy: processing")
        assertNull(reader.read("End file list"))
        assertFalse(reader.isReading())

        // and the same reader answers the next M20 normally
        reader.read("Begin file list")
        reader.read("TEST.GCO 47")
        assertEquals(SdFileList(listOf(SdFile("TEST.GCO", 47))), reader.read("End file list"))
    }

    @Test
    fun `a second Begin starts again rather than merging`() {
        val reader = SdFileListReader()
        reader.read("Begin file list")
        reader.read("A.GCO 1")
        reader.read("Begin file list")
        reader.read("B.GCO 2")

        assertEquals(SdFileList(listOf(SdFile("B.GCO", 2))), reader.read("End file list"))
    }

    @Test
    fun `the block round-trips through the reader`() {
        // SdFileList.encode() writes three lines or more, so there is no GRsDecoder to round-trip
        // it through. Feeding the lines back to a reader is the equivalent claim, and it is the
        // only one available for a value that is a block.
        for (list in listOf(
            SdFileList(emptyList()),
            SdFileList(listOf(SdFile("TEST.GCO", 47))),
            SdFileList(listOf(SdFile("TEST.GCO", 47), SdFile("BENCHY.GCO", 2847561))),
        )) {
            val reader = SdFileListReader()
            var back: SdFileList? = null
            for (line in list.encode().split("\n")) {
                val assembled = reader.read(line)
                if (assembled != null) back = assembled
            }
            assertEquals(list, back) { "round trip failed for: ${list.encode()}" }
        }
    }

    @Test
    fun `encode writes the block the firmware would send`() {
        assertEquals(
            "Begin file list\nTEST.GCO 47\nEnd file list",
            SdFileList(listOf(SdFile("TEST.GCO", 47))).encode(),
        )
        assertEquals("Begin file list\nEnd file list", SdFileList(emptyList()).encode())
    }

    @Test
    fun `a long filename with blanks in it keeps them`() {
        // The size is always the last field, so the name runs to the **last** blank rather than the
        // first. A build with LONG_FILENAME_HOST_SUPPORT writes names like this one.
        assertEquals(
            SdFileList(listOf(SdFile("my little benchy.gcode", 2847561))),
            read("Begin file list", "my little benchy.gcode 2847561", "End file list"),
        )
    }

    @Test
    fun `a tab separates the size as a space does`() {
        assertEquals(
            SdFileList(listOf(SdFile("TEST.GCO", 47))),
            read("Begin file list", "TEST.GCO\t47", "End file list"),
        )
    }

    @Test
    fun `a file larger than Int decodes`() {
        assertEquals(
            SdFileList(listOf(SdFile("HUGE.GCO", 3_000_000_000L))),
            read("Begin file list", "HUGE.GCO 3000000000", "End file list"),
        )
    }

    @Test
    fun `a size too wide for a Long abandons the listing rather than throwing`() {
        // spec 9: malformed input off the wire is a value, never a crash. The line is not an entry,
        // so the listing it was in cannot be vouched for.
        assertNull(read("Begin file list", "HUGE.GCO 99999999999999999999", "End file list"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["TEST.GCO", "TEST.GCO x", "47", " 47", "TEST.GCO 4 7x", "TEST.GCO -1"])
    fun `a line that is not an entry abandons the listing`(line: String) {
        assertNull(read("Begin file list", line, "End file list"), "should not be an entry: $line")
    }

    @Test
    fun `the markers fold, and surrounding blanks are allowed`() {
        // equalsAscii folds ASCII letters and trims the line's edges, which is what every other
        // keyword reply in this module does - `ok`, `wait`, `Not SD printing`.
        assertEquals(SdFileListBegin, SdFileListBegin.decode("  Begin file list\r\n"))
        assertEquals(SdFileListBegin, SdFileListBegin.decode("BEGIN FILE LIST"))
        assertNull(SdFileListBegin.decode("Begin file list now"))
        assertNull(SdFileListBegin.decode("End file list"))
        assertNull(SdFileListEnd.decode("Begin file list"))
    }
}
