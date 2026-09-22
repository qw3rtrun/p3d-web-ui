package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Corpus level tests over `src/test/resources/marlin.gcode` - 300+ lines of real Marlin flavoured
 * G-code. These guard the lexer and the liner against regressions on realistic input rather than on
 * hand-written snippets.
 *
 * The corpus is parsed exactly as it is checked out - LF or CRLF - so these tests also guard the
 * line-ending handling on realistic input.
 */
class GCorpusTest {

    private val tokenizer = GTokenizer()

    private val corpus: String = requireNotNull(javaClass.getResourceAsStream("/marlin.gcode")) {
        "marlin.gcode fixture is missing from the test resources"
    }.readBytes().decodeToString()

    private val lines: List<String> = corpus.lines().let {
        if (it.last().isEmpty()) it.dropLast(1) else it
    }

    @Test
    fun `the corpus is big enough to be worth testing`() {
        assertTrue(lines.size > 300) { "expected a corpus of 300+ lines, got ${lines.size}" }
    }

    @Test
    fun `the whole corpus tokenizes without failing`() {
        val tokens = tokenizer.parse(corpus).toList()

        assertTrue(tokens.size > 2500) { "expected 2500+ tokens, got ${tokens.size}" }
        assertTrue(tokens.none { it is GUnknown && it.str.isEmpty() })
    }

    @Test
    fun `every line tokenizes into at least one token`() {
        lines.filter { it.isNotEmpty() }.forEach { line ->
            val tokens = tokenizer.parse(line).toList()

            assertTrue(tokens.isNotEmpty()) { "line [$line] produced no tokens" }
        }
    }

    @Test
    fun `the corpus contains the token kinds it is meant to exercise`() {
        val tokens = tokenizer.parse(corpus).toList()

        assertTrue(tokens.any { it is GLetter }) { "no command letters in the corpus" }
        assertTrue(tokens.any { it is GInt }) { "no integers in the corpus" }
        assertTrue(tokens.any { it is GFloat }) { "no decimals in the corpus" }
        assertTrue(tokens.any { it is GTailComment }) { "no tail comments in the corpus" }
        assertTrue(tokens.any { it is GQuotedString }) { "no quoted strings in the corpus" }
        assertTrue(tokens.any { it is GLineBreak }) { "no line breaks in the corpus" }
    }

    @Test
    fun `the liner splits the corpus into one line per line break`() {
        val parsed = GSemanticParser(tokenizer.parse(corpus).iterator()).asSequence().toList()

        assertEquals(lines.size, parsed.size)
        assertTrue(parsed.all { it.raw.isNotEmpty() }) { "a parsed line has no tokens" }
    }

    @Test
    fun `no line break is counted twice`() {
        val breaks = tokenizer.parse(corpus).count { it is GLineBreak }

        assertEquals(corpus.count { it == '\n' }, breaks)
    }

    @Test
    fun `only the documented lexer gaps produce unknown tokens`() {
        // Characters the lexer does not understand yet. Each entry is a documented gap:
        // '.' from file extensions, '/' '\' '~' '!' ':' '|' from paths, '#' from RS274 parameters,
        // ''' and ',' from prose in bare rest-of-line strings (GCODE_spec.md section 3.4a).
        // The set is expected to shrink as those are implemented.
        //
        // All ASCII: narrowing the character classes to 7-bit ASCII (spec section 1.1) added nothing
        // here, which is what proves the change is scoped - the corpus does have non-ASCII, but only
        // inside comments and quoted strings, where it stays content. See the two tests below.
        val expected = listOf("!", "#", "'", ",", ".", "/", ":", "\\", "|", "~")

        val actual = tokenizer.parse(corpus)
            .filterIsInstance<GUnknown>()
            .map { it.str }
            .distinct()
            .sorted()
            .toList()

        assertEquals(expected, actual)
    }

    @Test
    fun `no token outside a comment or a string carries a non ascii character`() {
        // GCODE_spec.md section 1.1 confines non-ASCII to comments and quoted strings. The corpus has
        // Cyrillic in both, so this is the test that separates "reclassified" from "broken".
        val offenders = tokenizer.parse(corpus)
            .filter { it !is GComment && it !is GQuotedString }
            .map { it.rawText() }
            .filter { text -> text.any { it.code > 127 } }
            .distinct()
            .toList()

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the corpus still carries the non ascii characters of its comments`() {
        // U+2019 on line 140 and U+00B5 on lines 380-382. The fixture has no non-ASCII quoted string,
        // so the string half of the section 1.1 carve-out is pinned in GTokenizerTest instead.
        val nonAscii = tokenizer.parse(corpus)
            .filterIsInstance<GComment>()
            .flatMap { comment -> comment.string.asSequence() }
            .filter { it.code > 127 }
            .distinct()
            .sorted()
            .toList()

        assertEquals(listOf('\u00B5', '\u2019'), nonAscii)
    }

    @Test
    fun `no comment text carries a stray carriage return`() {
        // The fixture is CRLF. GCODE_spec.md section 1.2 makes both characters of a CRLF part of the
        // terminator, so neither belongs to the comment text; the CR is emitted by GLineBreak.
        val withCr = tokenizer.parse(corpus)
            .filterIsInstance<GTailComment>()
            .filter { it.string.contains('\r') }
            .map { it.string }
            .toList()

        assertEquals(0, withCr.size) {
            "${withCr.size} comments still carry a CR, e.g. [${withCr.firstOrNull()}]"
        }
    }

    @Test
    fun `the whole corpus round trips byte for byte including its terminators`() {
        // The per-line test below strips terminators, so it cannot see a CR moving between a comment
        // and its line break. This one parses the file exactly as checked out.
        assertEquals(corpus, tokenizer.parse(corpus).joinToString("") { it.rawText() })
    }

    @Test
    fun `each line round trips through the token stream`() {
        val failing = lines.filter { line ->
            runCatching { tokenizer.parse(line).joinToString("") { it.rawText() } }.getOrNull() != line
        }

        // No quarantine: number tokens carry their original lexeme, so every line of the corpus
        // reproduces itself byte for byte.
        assertEquals(emptyList<String>(), failing)
    }
    @Test
    fun `the liner classifies every corpus line and loses nothing`() {
        val parsed = GSemanticParser(tokenizer.parse(corpus).iterator()).asSequence().toList()
        val kinds = parsed.groupingBy { it::class.simpleName!! }.eachCount()

        // The corpus is a file, not a serial capture: it has commands, blank/comment-only lines,
        // and exactly two `N` lines with no checksum. GCODE_spec.md section 7.3 makes the last pair
        // a structural error over a link; in a file it is benign, which is why the liner reports
        // the structure rather than refusing the line. Pinned so the ratio cannot drift silently.
        assertEquals(setOf("GSimpleLine", "GMeaninglessLine", "GMissingChecksum"), kinds.keys)
        assertEquals(2, kinds["GMissingChecksum"])
        assertEquals(lines.size, parsed.size)
        assertTrue(parsed.none { it is GPacketLine }) { "the corpus has no checksummed lines" }
    }

    @Test
    fun `the two unchecksummed line numbers are the ones the fixture actually contains`() {
        val parsed = GSemanticParser(tokenizer.parse(corpus).iterator()).asSequence().toList()

        assertEquals(
            listOf("line number 100 has no checksum", "line number 101 has no checksum"),
            parsed.filterIsInstance<GError>().map { it.msg }
        )
    }

    /**
     * The second corpus: `marlin-packets.gcode`, a host-to-firmware session with every line framed
     * per GCODE_spec.md sections 7 and 8. The file corpus above deliberately has no checksummed
     * lines at all - it is a sliced file, not a serial capture - which left sections 7 and 8 with no
     * realistic regression net once verification started happening.
     *
     * **Its checksums were computed outside this module**, from the spec, not by `XorCheckSum` or
     * `Crc16CheckSum`. A fixture generated by `GEncoder` would agree with the verifier by
     * construction and would go on agreeing with it through a shared bug.
     */
    @Nested
    inner class PacketCorpus {

        private val packets: String = requireNotNull(javaClass.getResourceAsStream("/marlin-packets.gcode")) {
            "marlin-packets.gcode fixture is missing from the test resources"
        }.readBytes().decodeToString()

        private val packetLines: List<String> = packets.lines().let {
            if (it.last().isEmpty()) it.dropLast(1) else it
        }

        private fun parse(text: String): List<GLine> =
            GSemanticParser(tokenizer.parse(text).iterator()).asSequence().toList()

        @Test
        fun `every line verifies`() {
            val parsed = parse(packets)
            val notPackets = parsed.filter { it !is GPacketLine }

            assertEquals(emptyList<GLine>(), notPackets) {
                "these did not verify: " + notPackets.joinToString("; ") { line ->
                    line.raw.joinToString("") { it.rawText() }.trim()
                }
            }
            assertEquals(packetLines.size, parsed.size)
        }

        @Test
        fun `the corpus exercises both algorithms`() {
            // Otherwise a CRC regression could hide behind 28 passing XOR lines.
            val widths = parse(packets)
                .filterIsInstance<GPacketLine>()
                .groupingBy { it.checksum.lexeme.length }
                .eachCount()

            assertTrue(widths.keys.any { it in 1..3 }) { "no XOR-checksummed lines: $widths" }
            assertEquals(6, widths[5]) { "expected 6 CRC lines, got $widths" }
        }

        @Test
        fun `the line numbers are contiguous from zero`() {
            val numbers = parse(packets).filterIsInstance<GPacketLine>().map { it.number.int }

            assertEquals((0 until packetLines.size).toList(), numbers)
        }

        @Test
        fun `the packet corpus round-trips byte for byte`() {
            // A GPacketLine decomposes its line, so it is the type that can silently lose the `N`
            // field, the `*` field or the terminator.
            val reassembled = parse(packets).joinToString("") { line ->
                line.raw.joinToString("") { it.rawText() }
            }

            assertEquals(packets, reassembled)
        }

        @Test
        fun `corrupting any single byte of any line is caught`() {
            // The property the whole of section 8 exists for, run over realistic traffic rather
            // than one hand-written line: every byte of every covered range, one at a time.
            for (line in packetLines) {
                val star = line.lastIndexOf('*')
                for (i in 0 until star) {
                    val corrupted = line.substring(0, i) + flip(line[i]) + line.substring(i + 1)
                    val parsed = parse(corrupted).single()

                    assertTrue(parsed !is GPacketLine) {
                        "corruption at index $i survived verification: $corrupted"
                    }
                }
            }
        }

        /** Changes a character to a different legal one, so the line stays lexically well formed. */
        private fun flip(ch: Char): Char = when {
            ch in '0'..'8' -> ch + 1
            ch == '9' -> '0'
            ch == ' ' -> '\t'
            ch in 'A'..'Y' -> ch + 1
            ch == 'Z' -> 'A'
            ch == '.' -> '5'
            else -> 'Q'
        }
    }
}
