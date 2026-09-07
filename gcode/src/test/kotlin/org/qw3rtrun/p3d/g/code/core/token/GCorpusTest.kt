package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
        val parsed = GLineIterator(tokenizer.parse(corpus).iterator()).asSequence().toList()

        assertEquals(lines.size, parsed.size)
        assertTrue(parsed.all { it.payload.isNotEmpty() }) { "a parsed line has an empty payload" }
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
        val parsed = GLineIterator(tokenizer.parse(corpus).iterator()).asSequence().toList()
        val kinds = parsed.groupingBy { it::class.simpleName!! }.eachCount()

        // The corpus is a file, not a serial capture: it has commands, blank/comment-only lines,
        // and exactly two `N` lines with no checksum. GCODE_spec.md section 7.3 makes the last pair
        // a structural error over a link; in a file it is benign, which is why the liner reports
        // the structure rather than refusing the line. Pinned so the ratio cannot drift silently.
        assertEquals(setOf("GSimpleLine", "GEmptyLine", "GMissingChecksum"), kinds.keys)
        assertEquals(2, kinds["GMissingChecksum"])
        assertEquals(lines.size, parsed.size)
        assertTrue(parsed.none { it is GPacketLine }) { "the corpus has no checksummed lines" }
    }

    @Test
    fun `the two unchecksummed line numbers are the ones the fixture actually contains`() {
        val parsed = GLineIterator(tokenizer.parse(corpus).iterator()).asSequence().toList()

        assertEquals(
            listOf("line number 100 has no checksum", "line number 101 has no checksum"),
            parsed.filterIsInstance<GError>().map { it.msg }
        )
    }

}
