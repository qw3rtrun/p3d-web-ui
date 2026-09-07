package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Lexer tests - one nested group per token kind of GCODE_spec.md section 2, plus the iterator
 * contract, the input overloads and `rawText()` round-tripping.
 *
 * Deliberately not covered here (tracked in GCODE_TODO.md, tests come with the fixes):
 * signed numbers, leading-dot / trailing-dot / multi-dot decimals, leading zeros, tabs, CRLF,
 * unterminated strings and comments, `parseLines`, and the text *content* of inline comments.
 */
class GTokenizerTest {

    private val tokenizer = GTokenizer()

    private fun tokens(gcode: String): List<GToken> = tokenizer.parse(gcode).toList()

    private fun reprint(gcode: String): String = tokenizer.parse(gcode).joinToString("") { it.rawText() }

    @Nested
    inner class Letters {

        @Test
        fun `a single letter becomes one letter token`() {
            assertEquals(listOf(GLetter('G')), tokens("G"))
        }

        @Test
        fun `adjacent letters are separate tokens`() {
            assertEquals(listOf(GLetter('G'), GLetter('X'), GLetter('Y')), tokens("GXY"))
        }

        @Test
        fun `case is preserved`() {
            assertEquals(listOf(GLetter('g'), GLetter('m')), tokens("gm"))
        }

        @Test
        fun `a letter followed by a number is a word`() {
            assertEquals(listOf(GLetter('X'), GInt(10)), tokens("X10"))
        }
    }

    @Nested
    inner class Numbers {

        @Test
        fun `single digit integer`() {
            assertEquals(listOf(GInt(1)), tokens("1"))
        }

        @Test
        fun `multi digit integer`() {
            assertEquals(listOf(GInt(1800)), tokens("1800"))
        }

        @Test
        fun `zero`() {
            assertEquals(listOf(GInt(0)), tokens("0"))
        }

        @Test
        fun `decimal becomes a float token`() {
            assertEquals(listOf(GFloat("1.05")), tokens("1.05"))
        }

        @Test
        fun `decimal keeps trailing zeros`() {
            assertEquals(listOf(GFloat("1.050")), tokens("1.050"))
            assertEquals("1.050", reprint("1.050"))
        }

        @Test
        fun `a number stops at the next letter`() {
            assertEquals(listOf(GInt(10), GLetter('X')), tokens("10X"))
            assertEquals(listOf(GFloat("1.5"), GLetter('X')), tokens("1.5X"))
        }

        @Test
        fun `a number stops at whitespace`() {
            assertEquals(listOf(GInt(10), GSpace, GInt(20)), tokens("10 20"))
        }

        @Test
        fun `a number stops at a line break`() {
            assertEquals(listOf(GInt(10), GLineBreak("\n")), tokens("10\n"))
        }

        @Test
        fun `a number at end of input is emitted`() {
            assertEquals(listOf(GLetter('F'), GInt(1800)), tokens("F1800"))
        }
    }

    @Nested
    inner class QuotedStrings {

        @Test
        fun `simple quoted string`() {
            assertEquals(listOf(GQuotedString("abc")), tokens("\"abc\""))
        }

        @Test
        fun `empty quoted string`() {
            assertEquals(listOf(GQuotedString("")), tokens("\"\""))
        }

        @Test
        fun `doubled quotes are unescaped into one quote`() {
            // GCODE_spec.md section 3.4 - "" is an escaped quote inside a quoted string.
            assertEquals(listOf(GQuotedString("Hello \"World\"")), tokens("\"Hello \"\"World\"\"\""))
        }

        @Test
        fun `quoted string is a parameter value`() {
            assertEquals(listOf(GLetter('I'), GQuotedString("x")), tokens("I\"x\""))
        }

        @Test
        fun `a letter directly after the closing quote is not swallowed`() {
            assertEquals(listOf(GQuotedString("a"), GLetter('B')), tokens("\"a\"B"))
        }

        @Test
        fun `two quoted strings in a row`() {
            assertEquals(listOf(GQuotedString("a"), GSpace, GQuotedString("b")), tokens("\"a\" \"b\""))
        }

        @Test
        fun `separators and comment markers inside a string are literal content`() {
            assertEquals(listOf(GQuotedString("a b;c(d){e}*f")), tokens("\"a b;c(d){e}*f\""))
        }

        @Test
        fun `non ascii content inside a string is preserved`() {
            assertEquals(listOf(GQuotedString("Привет!")), tokens("\"Привет!\""))
        }
    }

    @Nested
    inner class TailComments {

        @Test
        fun `tail comment runs to end of input`() {
            assertEquals(listOf(GTailComment("abc")), tokens(";abc"))
        }

        @Test
        fun `tail comment ends at the line break which stays a separate token`() {
            assertEquals(listOf(GTailComment("abc"), GLineBreak("\n")), tokens(";abc\n"))
        }

        @Test
        fun `empty tail comment`() {
            assertEquals(listOf(GTailComment("")), tokens(";"))
        }

        @Test
        fun `tail comment keeps leading space and inner punctuation`() {
            assertEquals(listOf(GTailComment(" move \"fast\" (now) *5 {x}")), tokens("; move \"fast\" (now) *5 {x}"))
        }

        @Test
        fun `tail comment after a command`() {
            assertEquals(
                listOf(GLetter('G'), GInt(1), GSpace, GTailComment(" c")),
                tokens("G1 ; c")
            )
        }

        @Test
        fun `non ascii tail comment is preserved`() {
            assertEquals(listOf(GTailComment("Припарковать оси")), tokens(";Припарковать оси"))
        }
    }

    @Nested
    inner class InlineComments {

        // The *text* of an inline comment is not asserted here: it currently keeps the closing
        // paren (GCODE_TODO.md 1.2). Structure - one token per comment group, and correct
        // resumption of the line afterwards - is asserted instead.

        @Test
        fun `an inline comment is a single token`() {
            val result = tokens("(msg)")

            assertEquals(1, result.size)
            assertInstanceOf(GInlineComment::class.java, result[0])
        }

        @Test
        fun `the line resumes after an inline comment`() {
            val result = tokens("(msg)X")

            assertEquals(2, result.size)
            assertInstanceOf(GInlineComment::class.java, result[0])
            assertEquals(GLetter('X'), result[1])
        }

        @Test
        fun `two inline comments are two tokens`() {
            val result = tokens("(a)(b)")

            assertEquals(2, result.size)
            assertTrue(result.all { it is GInlineComment }) { "expected two inline comments, got $result" }
        }

        @Test
        fun `nested parentheses stay inside one comment token`() {
            val result = tokens("((a))X")

            assertEquals(2, result.size)
            assertInstanceOf(GInlineComment::class.java, result[0])
            assertEquals(GLetter('X'), result[1])
        }

        @Test
        fun `an inline comment does not end the line`() {
            val result = tokens("G1 (c) F1\n")

            assertEquals(1, result.count { it is GInlineComment })
            assertEquals(GLineBreak("\n"), result.last())
            assertEquals(listOf(GLetter('G'), GLetter('F')), result.filterIsInstance<GLetter>())
        }
    }

    @Nested
    inner class Expressions {

        @Test
        fun `expression keeps its braces`() {
            assertEquals(listOf(GRawExpression("{a}")), tokens("{a}"))
        }

        @Test
        fun `empty expression`() {
            assertEquals(listOf(GRawExpression("{}")), tokens("{}"))
        }

        @Test
        fun `nested braces stay inside one expression token`() {
            assertEquals(listOf(GRawExpression("{a{b}c}")), tokens("{a{b}c}"))
        }

        @Test
        fun `expression is a parameter value`() {
            assertEquals(
                listOf(GLetter('S'), GRawExpression("{first_layer_bed_temperature[0]}")),
                tokens("S{first_layer_bed_temperature[0]}")
            )
        }

        @Test
        fun `the line resumes after an expression`() {
            assertEquals(listOf(GRawExpression("{a}"), GLetter('X')), tokens("{a}X"))
        }

        @Test
        fun `separators quotes and parens inside an expression are literal content`() {
            assertEquals(listOf(GRawExpression("{a \"b\" ;c (d)}")), tokens("{a \"b\" ;c (d)}"))
        }
    }

    @Nested
    inner class Separators {

        // Tabs and CRLF are not asserted here - see GCODE_TODO.md 1.12 and 1.15.

        @Test
        fun `a space is a space token`() {
            assertEquals(listOf(GSpace), tokens(" "))
        }

        @Test
        fun `repeated spaces are separate tokens`() {
            assertEquals(listOf(GSpace, GSpace, GSpace), tokens("   "))
        }

        @Test
        fun `LF is a line break`() {
            assertEquals(listOf(GLineBreak("\n")), tokens("\n"))
        }

        @Test
        fun `consecutive line breaks are separate tokens`() {
            assertEquals(listOf(GLineBreak("\n"), GLineBreak("\n")), tokens("\n\n"))
        }

        @Test
        fun `line break separates two commands`() {
            assertEquals(
                listOf(GLetter('G'), GInt(1), GLineBreak("\n"), GLetter('G'), GInt(2)),
                tokens("G1\nG2")
            )
        }
    }

    @Nested
    inner class Checksum {

        @Test
        fun `a star is the checksum marker`() {
            assertEquals(listOf(GChecksum), tokens("*"))
        }

        @Test
        fun `checksum marker is followed by its value`() {
            assertEquals(listOf(GChecksum, GInt(57)), tokens("*57"))
            assertEquals(listOf(GChecksum, GInt(0)), tokens("*0"))
        }

        @Test
        fun `a star inside a tail comment is not a checksum marker`() {
            val result = tokens("; 3*4")

            assertEquals(listOf(GTailComment(" 3*4")), result)
            assertFalse(result.contains(GChecksum))
        }

        @Test
        fun `a star inside a quoted string is not a checksum marker`() {
            val result = tokens("\"3*4\"")

            assertEquals(listOf(GQuotedString("3*4")), result)
            assertFalse(result.contains(GChecksum))
        }
    }

    @Nested
    inner class UnknownCharacters {

        @ParameterizedTest
        @ValueSource(strings = ["?", "@", "!", ":", ",", "|", "~", "'", "%", "=", "&", "^", "<", ">"])
        fun `unsupported characters become unknown tokens`(ch: String) {
            assertEquals(listOf(GUnknown(ch)), tokens(ch))
        }

        @Test
        fun `an unknown character does not disturb the surrounding tokens`() {
            assertEquals(
                listOf(GLetter('G'), GInt(1), GUnknown("?"), GLetter('X'), GInt(2)),
                tokens("G1?X2")
            )
        }

        @Test
        fun `each unknown character is its own token`() {
            assertEquals(listOf(GUnknown("?"), GUnknown("@")), tokens("?@"))
        }
    }

    @Nested
    inner class IteratorContract {

        @Test
        fun `empty input produces no tokens`() {
            assertEquals(emptyList<GToken>(), tokens(""))
        }

        @Test
        fun `an exhausted iterator reports no next element`() {
            val iter = tokenizer.parse("G".iterator())

            assertTrue(iter.hasNext())
            assertEquals(GLetter('G'), iter.next())
            assertFalse(iter.hasNext())
        }

        @Test
        fun `next on an exhausted iterator throws`() {
            // The source iterator is a List one on purpose: `next()` does not guard on `hasNext()`
            // itself, it propagates whatever the character source throws (GCODE_TODO.md 1.17).
            val iter = tokenizer.parse(emptyList<Char>().iterator())

            assertFalse(iter.hasNext())
            assertThrows<NoSuchElementException> { iter.next() }
        }

        @Test
        fun `hasNext is idempotent and does not consume`() {
            val iter = tokenizer.parse("XY".iterator())

            assertTrue(iter.hasNext())
            assertTrue(iter.hasNext())
            assertEquals(GLetter('X'), iter.next())
            assertTrue(iter.hasNext())
            assertTrue(iter.hasNext())
            assertEquals(GLetter('Y'), iter.next())
            assertFalse(iter.hasNext())
            assertFalse(iter.hasNext())
        }
    }

    @Nested
    inner class InputOverloads {

        private val source = "G1 X10.5"
        private val expected = listOf(
            GLetter('G'), GInt(1), GSpace, GLetter('X'), GFloat("10.5")
        )

        @Test
        fun `char sequence`() {
            assertEquals(expected, tokenizer.parse(source).toList())
        }

        @Test
        fun `char iterator`() {
            assertEquals(expected, tokenizer.parse(source.iterator()).asSequence().toList())
        }

        @Test
        fun `char iterable`() {
            assertEquals(expected, tokenizer.parse(source.toList()).toList())
        }

        @Test
        fun `char sequence of chars`() {
            assertEquals(expected, tokenizer.parse(source.asSequence()).toList())
        }

        @Test
        fun `char stream`() {
            assertEquals(expected, tokenizer.parse(source.toList().stream()).toList())
        }
    }

    @Nested
    inner class WholeLines {

        @Test
        fun `plain move command`() {
            assertEquals(
                listOf(
                    GLetter('G'), GInt(1), GSpace,
                    GLetter('X'), GFloat("10.5"), GSpace,
                    GLetter('Y'), GInt(20), GSpace,
                    GLetter('F'), GInt(1800),
                    GLineBreak("\n")
                ),
                tokens("G1 X10.5 Y20 F1800\n")
            )
        }

        @Test
        fun `numbered line with checksum and comment`() {
            assertEquals(
                listOf(
                    GLetter('N'), GInt(42), GSpace,
                    GLetter('G'), GInt(1), GSpace,
                    GLetter('X'), GFloat("10.5"), GSpace,
                    GLetter('F'), GInt(1800),
                    GChecksum, GInt(9), GSpace,
                    GTailComment(" move"),
                    GLineBreak("\n")
                ),
                tokens("N42 G1 X10.5 F1800*9 ; move\n")
            )
        }

        @Test
        fun `message command with a quoted string`() {
            assertEquals(
                listOf(GLetter('M'), GInt(117), GSpace, GQuotedString("Hello World!"), GLineBreak("\n")),
                tokens("M117 \"Hello World!\"\n")
            )
        }

        @Test
        fun `slicer placeholder expression with a comment`() {
            assertEquals(
                listOf(
                    GLetter('M'), GInt(140), GSpace,
                    GLetter('S'), GRawExpression("{first_layer_bed_temperature[0]}"), GSpace,
                    GTailComment("heat the bed"),
                    GLineBreak("\n")
                ),
                tokens("M140 S{first_layer_bed_temperature[0]} ;heat the bed\n")
            )
        }
    }

    @Nested
    inner class RoundTrip {

        @ParameterizedTest
        @ValueSource(
            strings = [
                "",
                "G28",
                "G1 X10.5 Y20 F1800",
                "G1X10.5Y20F1800",
                "M117 \"Hello World!\"",
                "M11 I   \"Hello \"\"World\"\"\"",
                "M140 S{first_layer_bed_temperature[0]}",
                "M{SET_TEMP} F{CURRENT}X1.05",
                "; a whole line comment",
                "G1 F100 ; trailing comment with \" and * and (",
                "N42 G1 X1 F100*9",
                "\n",
                "\n\n",
                "G28\n",
                "G1 X1\nG1 X2\n",
                "M117 \"Привет, мир!\" ;комментарий"
            ]
        )
        fun `raw text of the token stream reproduces the input`(gcode: String) {
            assertEquals(gcode, reprint(gcode))
        }

        @Test
        fun `multi line program round trips`() {
            // Built by explicit concatenation rather than a raw string: the line terminator has to
            // stay LF regardless of how the source file itself is checked out.
            val program = listOf(
                "; header",
                "G28",
                "G1 X10.5 Y20.05 F1800 ; move",
                "M117 \"Hello \"\"World\"\"!\"",
                "M140 S{bed[0]}",
                "N42 G1 X1 F100*9",
                "",
                "G90"
            ).joinToString("\n")

            assertEquals(program, reprint(program))
        }

        @Test
        fun `slicer start block round trips`() {
            assertEquals(BEFORE_GCODE, reprint(BEFORE_GCODE))
        }

        @Test
        fun `slicer end block round trips`() {
            assertEquals(AFTER_GCODE, reprint(AFTER_GCODE))
        }
    }

    companion object {

        /** Real PrusaSlicer start block: placeholder expressions plus non-ASCII tail comments. */
        private const val BEFORE_GCODE =
            "M140 S{first_layer_bed_temperature[0]} ;Запустить нагрев стола до указанной в профиле филамента температуры\n" +
                    "M104 S150 ;Запустить нагрев хотэнда до 150 градусов\n" +
                    "G28 ;Припарковать все оси\n" +
                    "M104 S{first_layer_temperature[0]} ;Запустить нагрев хотэнда до указанной в профиле филамента температуры\n" +
                    "M190 S{first_layer_bed_temperature[0]} ;Ожидать нагрева стола до указанной в профиле филамента температуры\n" +
                    "M109 S{first_layer_temperature[0]} ;Ожидать нагрева хотэнда до указанной в профиле филамента температуры\n" +
                    "G90 ;Установить абсолютную систему координат для всех осей\n" +
                    "M220 S100 ;Установить поток в прошивке на 100%\n" +
                    "M221 S100 ;Установить множитель скорости в прошивке на 100%"

        /** Real PrusaSlicer end block. */
        private const val AFTER_GCODE = "M140 S0 ;Выключить нагрев стола\n" +
                "M106 S0 ;Выключить вентилятор обдува модели\n" +
                "G91 ;Относительная система координат\n" +
                "G1 E-5 F1800 ;Сделать откат 5мм\n" +
                "M104 S0 ;Выключить нагрев хотэнда\n" +
                "G1 Z0.2 F300 ;Поднять печатающую голову на 0.2 мм\n" +
                "G90 ;Абсолютная система координат\n" +
                "G1 X5 Y5 F6000 ;Переехать в координату 5;5\n" +
                "M84 ;Выключить моторы"
    }
}
