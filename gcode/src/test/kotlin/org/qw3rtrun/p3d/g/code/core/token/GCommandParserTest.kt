package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The word->command layer, spec sections 4 and 5. `GSemanticParser` answers what *shape* a line has;
 * this answers what *commands* it carries.
 */
class GCommandParserTest {

    private val tokenizer = GTokenizer()
    private val parser = GCommandParser()

    /** The commands of the single line in [gcode]. */
    private fun commands(gcode: String): List<GCommand> =
        parser.parse(GSemanticParser(tokenizer.parse(gcode).iterator()).next())

    @Nested
    inner class OneCommand {

        @Test
        fun `a command with no parameters`() {
            assertEquals(listOf(GCommand(GLetter('G'), GInt(28))), commands("G28"))
        }

        @Test
        fun `a command with parameters`() {
            assertEquals(
                listOf(
                    GCommand(
                        GLetter('G'), GInt(1),
                        listOf(
                            GParameterWord(GLetter('X'), GFloat("10.5")),
                            GParameterWord(GLetter('F'), GInt(1800)),
                        )
                    )
                ),
                commands("G1 X10.5 F1800")
            )
        }

        @Test
        fun `a lower case command letter is the same command`() {
            assertEquals(listOf(GCommand(GLetter('g'), GInt(28))), commands("g28"))
        }

        @Test
        fun `leading zeros are insignificant but the lexeme is kept`() {
            val cmd = commands("G01").single()

            assertEquals(GInt(1, "01"), cmd.head.value)
            assertEquals("G01", cmd.head.raw.joinToString("") { it.rawText() })
        }

        @Test
        fun `T heads a command when it is the first field`() {
            assertEquals(listOf(GCommand(GLetter('T'), GInt(0))), commands("T0"))
        }
    }

    @Nested
    inner class Whitespace {

        /** spec 2.1: whitespace separates, so it never splits a word or changes the command. */
        @ParameterizedTest
        @ValueSource(strings = ["G1 X10", "G1 X 10", "G1  X  10", "G 1 X10", "\tG1 X10"])
        fun `whitespace around a word does not change the command`(gcode: String) {
            val cmd = commands(gcode).single()

            assertTrue(cmd.head.isLetter('G')) { "expected a G command in '$gcode', got $cmd" }
            assertEquals(GInt(1), cmd.head.value)
            assertEquals(listOf(GParameterWord(GLetter('X'), GInt(10))), cmd.params.map { strip(it) })
        }

        /** Compares words by identity and value, ignoring the raw tokens whitespace changes. */
        private fun strip(w: GWord): GWord =
            if (w is GParameterWord<*>) GParameterWord(w.id, w.value) else GFlagWord(w.id)
    }

    @Nested
    inner class Flags {

        /** spec 3.2: a letter with no value is a word - "parameter present". */
        @Test
        fun `value-less parameters are flag words`() {
            assertEquals(
                listOf(
                    GCommand(
                        GLetter('G'), GInt(28),
                        listOf(GFlagWord(GLetter('X')), GFlagWord(GLetter('Y')))
                    )
                ),
                commands("G28 X Y")
            )
        }

        @Test
        fun `a bare command letter is not a command`() {
            // spec 4.1: a command word is a letter followed by an unsigned integer. `G` alone is a
            // flag word, so the line carries no command at all.
            assertEquals(emptyList<GCommand>(), commands("G"))
        }
    }

    @Nested
    inner class Subcodes {

        /** spec 4.1: a command number may carry `.` plus an unsigned integer. */
        @Test
        fun `a subcode stays on the command number`() {
            val cmd = commands("G29.1").single()

            assertEquals(GFloat("29.1"), cmd.head.value)
        }

        @Test
        fun `a subcode re-emits as one number, not a number and a dot`() {
            val cmd = commands("G29.1 P2").single()

            assertEquals("G29.1", cmd.head.raw.joinToString("") { it.rawText() })
        }

        @Test
        fun `a trailing dot is not a subcode`() {
            // spec 4.1 wants `.` *plus an unsigned integer*; `G29.` has no subcode digits.
            assertEquals(emptyList<GCommand>(), commands("G29."))
        }

        @Test
        fun `a signed command number is not a command`() {
            // spec 4.1: unsigned.
            assertEquals(emptyList<GCommand>(), commands("G-1 X5"))
        }
    }

    @Nested
    inner class SeveralCommands {

        /**
         * spec 4.3: multiple commands on one line are not portable and Marlin executes only the
         * first, but the parser reports what is there and leaves that policy to the caller.
         */
        @Test
        fun `G and M each start a new command`() {
            assertEquals(
                listOf(
                    GCommand(GLetter('G'), GInt(1), listOf(GParameterWord(GLetter('X'), GInt(1)))),
                    GCommand(GLetter('M'), GInt(104), listOf(GParameterWord(GLetter('S'), GInt(200)))),
                ),
                commands("G1 X1 M104 S200")
            )
        }

        /**
         * spec 4.1/4.2: `T` is a command letter *and* a conventional parameter letter. After a
         * command has started it is that command's parameter - 40 corpus lines (`G29 T`,
         * `G12 P1 S1 T3`, `G26 C P T3.0`) depend on this, and splitting at `T` misparses all of them.
         */
        @Test
        fun `T after a command is that command's parameter`() {
            assertEquals(
                listOf(
                    GCommand(
                        GLetter('G'), GInt(12),
                        listOf(
                            GParameterWord(GLetter('P'), GInt(1)),
                            GParameterWord(GLetter('S'), GInt(1)),
                            GParameterWord(GLetter('T'), GInt(3)),
                        )
                    )
                ),
                commands("G12 P1 S1 T3")
            )
        }

        @Test
        fun `a bare T after a command is a flag parameter`() {
            assertEquals(
                listOf(GCommand(GLetter('G'), GInt(29), listOf(GFlagWord(GLetter('T'))))),
                commands("G29 T")
            )
        }
    }

    @Nested
    inner class LinesWithoutCommands {

        @Test
        fun `a comment only line has no commands`() {
            assertEquals(emptyList<GCommand>(), commands("; just a comment"))
        }

        @Test
        fun `an empty word list has no commands`() {
            assertEquals(emptyList<GCommand>(), parser.parse(emptyList()))
        }

        /**
         * spec 4.1: with `GCODE_MOTION_MODES` an axis letter may start a line and inherit the last
         * motion command. Resolving that needs the previous line, which this parser does not have -
         * so such a line reports no command rather than guessing one. Characterisation point for
         * [05](https://github.com/qw3rtrun/p3d-web-ui/issues/7), which owns
         * session state; the corpus contains no such line.
         */
        @Test
        fun `a line of only parameters reports no command`() {
            assertEquals(emptyList<GCommand>(), commands("X10 Y10"))
        }

        /** spec 4.3: parameter order is not significant, so a leading run joins the first command. */
        @Test
        fun `parameters before the command letter join that command`() {
            assertEquals(
                listOf(GCommand(GLetter('G'), GInt(1), listOf(GParameterWord(GLetter('X'), GInt(10))))),
                commands("X10 G1")
            )
        }
    }

    @Nested
    inner class StructuralFields {

        /** spec 7/8: `N` and `*` are structural, never commands or parameters of one. */
        @Test
        fun `a packet line's commands exclude the line number and the checksum`() {
            assertEquals(
                listOf(GCommand(GLetter('G'), GInt(28))),
                commands("N1 G28*18\n")
            )
        }

        /**
         * spec 4: the same holds when the line is *not* a well-formed packet. A `GPacketLine` keeps
         * `N` out of its payload, but a line number with no checksum keeps its word - so the skip
         * has to be here, not in the line shape. Without it `N100 M110` re-emitted as `M110N100`.
         */
        @Test
        fun `an unpaired line number is not a parameter of the command`() {
            assertEquals(listOf(GCommand(GLetter('M'), GInt(110))), commands("N100 M110"))
        }

        /** spec 8.1: a bare `*` is a flag word, and still structural rather than a parameter. */
        @Test
        fun `a checksum marker with no value is not a parameter of the command`() {
            assertEquals(listOf(GCommand(GLetter('G'), GInt(28))), commands("N1 G28*"))
        }

        /**
         * Characterisation point. spec 5 puts `*<checksum>` last and says nothing about content
         * after it, so the letters of a garbled field become flag parameters: `*ABC` lexes as `*`
         * plus three separate letters, not one token. The shape layer already reports this line as
         * `GMalformedChecksum`, so a caller reading commands off it has an error in hand either way.
         * If a tail concept lands on the line types (todo 04 wants one for spec 8.3), exclude it
         * here too and this assertion changes.
         */
        @Test
        fun `letters after a garbled checksum become flag parameters`() {
            assertEquals(
                listOf(
                    GCommand(
                        GLetter('G'), GInt(28),
                        listOf(GFlagWord(GLetter('A')), GFlagWord(GLetter('B')), GFlagWord(GLetter('C')))
                    )
                ),
                commands("N1 G28*ABC")
            )
        }
    }

    @Nested
    inner class Corpus {

        private val corpus: String = requireNotNull(javaClass.getResourceAsStream("/marlin.gcode")) {
            "marlin.gcode fixture is missing from the test resources"
        }.readBytes().decodeToString()

        private fun lines(): List<GLine> =
            GSemanticParser(tokenizer.parse(corpus).iterator()).asSequence().toList()

        @Test
        fun `word assembly over the whole corpus does not throw`() {
            assertDoesNotThrow { lines().forEach { parser.parse(it) } }
        }

        /**
         * A census, so the command/parameter ratio cannot drift silently. `T` heading a command only
         * while none has started is what keeps this at one command per line for all but three lines,
         * which are pinned below - splitting at every `T` instead turns 40 lines into two commands.
         */
        @Test
        fun `the corpus is one command per line, with three named exceptions`() {
            var withWords = 0
            val multi = ArrayList<String>()
            for (line in lines()) {
                if (parser.words(line.body).isEmpty()) continue
                withWords++
                val cmds = parser.parse(line)
                if (cmds.size != 1) {
                    multi.add(line.raw.joinToString("") { it.rawText() }.trim() + " -> " + cmds.size)
                }
            }

            assertTrue(withWords > 290) { "expected 290+ corpus lines with words, got $withWords" }
            assertEquals(
                listOf(
                    // spec 4.3: splitting at each G/M. G53 is a modal prefix - "move in machine
                    // coordinates" - so two commands here is the correct reading, not a misparse.
                    "G53 G0 X0 Y0 Z0 -> 2",
                    "G53 G1 X20 -> 2",
                    // M815's argument is a bare rest-of-line string, which needs the command number
                    // to lex (spec 3.4a, deferred to todo 09). Until then `|` lexes as an unknown
                    // token and the G/M words inside the argument split it. Characterisation point.
                    "M815 G0 X0 Y0|G0 Z10|M300 S440 P50 -> 4",
                ),
                multi
            )
        }

        /**
         * The property that catches a flag or a subcode being quietly dropped. Structural words are
         * excluded on both sides: spec 4 makes `N` and `*` belong to the line, not to a command.
         *
         * "Structural" means the `*` field and **the line number**, and the line number can only be
         * the first word (spec 7.1, and Marlin takes one only when `N` opens the line). An earlier
         * version of this filter excluded *every* `N` word, on both sides, so it agreed with the
         * parser while both were wrong together. What it hid: `M0 Click to continue` is a bare
         * rest-of-line string that today lexes letter by letter (todo 09), and every `n` in
         * "continue" was being silently deleted from the assembled command. The narrower rule is
         * what `M110 N<n>` needs as well - see `GCommandParser.isStructural`.
         */
        @Test
        fun `the commands of a corpus line re-emit its parametric words`() {
            for (line in lines()) {
                val words = parser.words(line.body).filterIndexed { i, w ->
                    val id = w.id
                    id != GChecksum && !(i == 0 && id is GLetter && (id.letter == 'N' || id.letter == 'n'))
                }
                if (words.isEmpty()) continue
                val expected = words.joinToString("") { w -> w.raw.joinToString("") { it.rawText() } }
                val actual = parser.parse(line).joinToString("") { cmd ->
                    cmd.head.raw.joinToString("") { it.rawText() } +
                        cmd.params.joinToString("") { w -> w.raw.joinToString("") { it.rawText() } }
                }

                assertEquals(expected, actual) {
                    "words lost on: " + line.raw.joinToString("") { it.rawText() }.trim()
                }
            }
        }
    }

    /**
     * Spec 7.1 puts the line number first, and only there. Everywhere else an `N` is an ordinary
     * parameter - which matters because exactly one command's argument is one.
     */
    @Nested
    inner class StructuralFieldsAreThePositionalOnes {

        @Test
        fun `M110 keeps its line-number argument`() {
            // spec 7.2: `M110 N<n>` is how a host sets the counter, so this parameter is the whole
            // point of the command. Skipping every `N` word assembled it as a bare `M110` and made
            // the command unreadable - found while building todo 05.
            assertEquals(
                listOf(GCommand(GLetter('M'), GInt(110), listOf(GParameterWord(GLetter('N'), GInt(7))))),
                commands("M110 N7")
            )
        }

        @Test
        fun `M110 keeps its argument on a framed line too`() {
            // Two `N`s: the first is the line number and belongs to the line, the second is the
            // argument and belongs to the command. This is the case Marlin special-cases by
            // searching for a second `N` (queue.cpp), so getting it wrong is not academic.
            assertEquals(
                listOf(GCommand(GLetter('M'), GInt(110), listOf(GParameterWord(GLetter('N'), GInt(7))))),
                commands("N1 M110 N7*123")
            )
        }

        @Test
        fun `a leading line number is still structural`() {
            // Corpus line 173. Without the skip the line number became a parameter of M110 and
            // re-emitted the line as `M110N100`, with the words in the wrong order.
            assertEquals(listOf(GCommand(GLetter('M'), GInt(110))), commands("N100 M110"))
        }

        @Test
        fun `an N that does not open the line is a parameter`() {
            // spec 7.1: the line number "should be the first field". Marlin only takes one when `N`
            // is the first character, so a later `N` is a parameter of whatever command precedes it.
            assertEquals(
                listOf(GCommand(GLetter('G'), GInt(1), listOf(GParameterWord(GLetter('N'), GInt(5))))),
                commands("G1 N5")
            )
        }

        @Test
        fun `the checksum field is structural wherever it appears`() {
            // Unlike `N`, `*` is structural by identity and not by position - spec 5 puts it last,
            // and a second one is still not a parameter.
            assertEquals(listOf(GCommand(GLetter('G'), GInt(28))), commands("N1 G28*18"))
            assertEquals(listOf(GCommand(GLetter('G'), GInt(28))), commands("N1 G28*12*59"))
        }
    }
}
