package org.qw3rtrun.p3d.g.code.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.*

/**
 * The rule, as a number: **any valid G-code can be written with the DSL**.
 *
 * Every line of the corpus is taken apart and *rewritten through the DSL's public API* - not rebuilt
 * from the parsed objects, which would only test the encoder - and the result is compared with the
 * original. A line the DSL cannot express fails here, so the rule cannot rot quietly.
 */
class GDslCorpusTest {

    private val tokenizer = GTokenizer()
    private val commands = GCommandParser()

    private val corpus: String =
        requireNotNull(javaClass.getResourceAsStream("/marlin.gcode")).readBytes().decodeToString()

    private fun lines(): List<GLine> =
        GSemanticParser(tokenizer.parse(corpus).iterator()).asSequence().toList()

    private fun raw(line: GLine): String =
        line.raw().filter { it !is GLineBreak }.joinToString("") { it.rawText() }.trim()

    /** Runs of spaces and tabs squeezed to one space - the encoder's canonical spacing. */
    private fun collapse(text: String): String {
        val out = StringBuilder()
        var space = false
        for (ch in text) {
            if (ch == ' ' || ch == '\t') { space = true; continue }
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.append(ch)
        }
        return out.toString()
    }

    /** Rebuilds [line] using only the DSL's public builders, or null if it cannot be said. */
    private fun rewrite(line: GLine): GBlock? {
        val parts = ArrayList<GBlockPart>()
        for (cmd in commands.parse(line)) {
            val id = cmd.head.id
            if (id !is GLetter) return null
            val params = ArrayList<GWord>()
            for (p in cmd.params) {
                val pid = p.id
                if (pid !is GLetter) return null
                params.add(
                    when (p) {
                        is GFlagWord -> flag(pid.letter)
                        is GParameterWord<*> -> when (val v = p.value) {
                            is GNumber -> word(pid.letter, v.lexeme)
                            is GQuotedString -> word(pid.letter, text(v.string))
                            is GRawExpression -> word(pid.letter, v)
                            else -> return null
                        }
                    }
                )
            }
            parts.add(command(id.letter, cmd.head.value.lexeme, *params.toTypedArray()))
        }
        for (token in line.raw()) {
            if (token is GTailComment) parts.add(tailComment(token.string))
            if (token is GInlineComment) parts.add(inlineComment(token.string))
        }
        if (parts.isEmpty()) return null
        return block(*parts.toTypedArray())
    }

    @Test
    fun `the DSL can express the corpus`() {
        var byteExact = 0
        var expressible = 0
        val inexpressible = ArrayList<String>()
        val notByteExact = ArrayList<String>()

        for (line in lines()) {
            val text = raw(line)
            if (text.isEmpty()) continue

            val rebuilt = rewrite(line)
            if (rebuilt == null) {
                inexpressible.add(text)
                continue
            }
            expressible++

            val encoded = GEncoder.encode(rebuilt)
            if (encoded == collapse(text)) byteExact++ else notByteExact.add("$text  ->  $encoded")
        }

        // The rule itself, with no quarantine: there is no line of real G-code the DSL cannot say.
        assertEquals(emptyList<String>(), inexpressible) {
            "the DSL cannot express ${inexpressible.size} corpus lines"
        }
        assertEquals(303, expressible)

        // And the stronger claim, byte for byte, modulo the encoder's canonical spacing. The 24
        // that differ are four named things, none of them a gap in the DSL:
        //
        //  * 20 bare rest-of-line strings - `M0 Click to continue`, `M23 /path/f.gco`. These need
        //    the command number to lex (spec 3.4a) and are deferred to todo 09; today they lex
        //    letter by letter, so what comes back is `M0 C l i c k ...`. A *model* gap, above the
        //    DSL, and the one thing here worth fixing.
        //  * 2 x `G29 F 10.0` - a space between a letter and its value. Spec 2.1 permits it and the
        //    parser reads it as one word; the encoder has no way to ask for it and never will,
        //    since emitting it would change the checksum for no gain.
        //  * 1 x `G61 XY S0` - two flags with no space between them. Same story: read as two words,
        //    written back as two words with the canonical separator.
        //  * 1 x `G92 .1 ;TODO` - a value with no letter in front of it. That is not a word at all
        //    (spec 4), so no command carries it and nothing can rebuild it from commands.
        assertEquals(279, byteExact) { "byte-exact count moved:\n" + notByteExact.joinToString("\n") }
        assertEquals(24, notByteExact.size)
    }
}
