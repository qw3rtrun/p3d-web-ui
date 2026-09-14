package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
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
        fun `a non ascii letter outside a comment or a string is unknown`() {
            // GCODE_spec.md section 1.1: the wire format is 7-bit ASCII, so a Cyrillic capital is a
            // lexical error (section 9), not a command letter.
            assertEquals(listOf(GLetter('G'), GUnknown("Я"), GInt(1)), tokens("GЯ1"))
        }

        @Test
        fun `a reclassified non ascii letter still round trips`() {
            assertEquals("GЯ1", reprint("GЯ1"))
        }

        @Test
        fun `latin letters with diacritics are unknown`() {
            assertEquals(listOf(GUnknown("é"), GUnknown("Å")), tokens("éÅ"))
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

        @Test
        fun `a decimal may start with a dot`() {
            assertEquals(listOf(GLetter('X'), GFloat(".5")), tokens("X.5"))
            assertEquals(listOf(GLetter('Y'), GFloat(".40")), tokens("Y.40"))
        }

        @Test
        fun `a leading dot decimal keeps its lexeme and its value`() {
            val result = tokens("X.5")

            assertEquals(".5", result[1].rawText())
            assertEquals(0, (result[1] as GFloat).value.compareTo(GFloat("0.5").value))
        }

        @Test
        fun `a leading dot decimal does not duplicate its first digit`() {
            assertEquals(2, tokens("X.5").size)
            assertEquals(listOf(GFloat(".5"), GLetter('X')), tokens(".5X"))
            assertEquals(listOf(GFloat(".5"), GSpace, GInt(5)), tokens(".5 5"))
        }

        @Test
        fun `a lone dot is unknown`() {
            assertEquals(listOf(GLetter('X'), GUnknown(".")), tokens("X."))
        }

        @Test
        fun `a dot that is not followed by a digit is unknown and keeps the next token`() {
            assertEquals(listOf(GLetter('X'), GUnknown("."), GLetter('A')), tokens("X.A"))
            assertEquals(listOf(GUnknown("."), GSpace, GLetter('X')), tokens(". X"))
        }

        @Test
        fun `a trailing dot is a real, lexeme included`() {
            // GCODE_spec.md section 3.1 lists `2.` among the real forms.
            val result = tokens("X1.")

            assertEquals(listOf(GLetter('X'), GFloat("1.")), result)
            assertInstanceOf(GNumber::class.java, result[1])
            assertEquals("X1.", reprint("X1."))
        }

        @Test
        fun `a negative integer includes its sign`() {
            // GCODE_spec.md section 3.1 - an optional sign is part of the number.
            assertEquals(listOf(GLetter('E'), GInt(-5)), tokens("E-5"))
            assertEquals(listOf(GLetter('P'), GInt(-1), GSpace, GLetter('S'), GInt(1)), tokens("P-1 S1"))
        }

        @Test
        fun `a negative real includes its sign`() {
            assertEquals(listOf(GLetter('Z'), GFloat("-0.2")), tokens("Z-0.2"))
            assertEquals(listOf(GLetter('X'), GFloat("-1.70")), tokens("X-1.70"))
            assertEquals(listOf(GLetter('X'), GFloat("-.5")), tokens("X-.5"))
        }

        @Test
        fun `a plus sign is kept in the lexeme`() {
            assertEquals(listOf(GLetter('S'), GInt(5, "+5")), tokens("S+5"))
            assertEquals(5, (tokens("S+5")[1] as GInt).int)
            assertEquals(listOf(GLetter('S'), GFloat("+1.5")), tokens("S+1.5"))
            assertEquals("S+5", reprint("S+5"))
        }

        @Test
        fun `a signed number stops at the next word`() {
            assertEquals(listOf(GLetter('E'), GInt(-5), GSpace, GLetter('F'), GInt(1800)), tokens("E-5 F1800"))
            assertEquals(listOf(GLetter('E'), GInt(-5), GLetter('F')), tokens("E-5F"))
            assertEquals(listOf(GLetter('E'), GInt(-5), GLineBreak("\n")), tokens("E-5\n"))
        }

        @Test
        fun `a sign that does not introduce a number is unknown`() {
            // GCODE_spec.md section 3.1 needs at least one digit somewhere in the number;
            // section 9 asks for a lexical error rather than a silently dropped character.
            assertEquals(listOf(GUnknown("-")), tokens("-"))
            assertEquals(listOf(GUnknown("+")), tokens("+"))
            assertEquals(listOf(GLetter('X'), GUnknown("-"), GLetter('Y')), tokens("X-Y"))
            assertEquals(listOf(GUnknown("-"), GSpace), tokens("- "))
            assertEquals(listOf(GUnknown("-"), GInt(-5)), tokens("--5"))
        }

        @Test
        fun `a sign with a dot but no digit is unknown`() {
            assertEquals(listOf(GUnknown("-.")), tokens("-."))
            assertEquals(listOf(GUnknown("-."), GLetter('X')), tokens("-.X"))
        }

        @Test
        fun `a signed number with two decimal points is unknown`() {
            assertEquals(listOf(GLetter('X'), GUnknown("-1.2.3")), tokens("X-1.2.3"))
            assertEquals("X-1.2.3", reprint("X-1.2.3"))
        }

        @Test
        fun `signed numbers round trip`() {
            assertEquals("G1 E-5 F1800", reprint("G1 E-5 F1800"))
            assertEquals("M851 X-1.70 Y-1.30", reprint("M851 X-1.70 Y-1.30"))
            assertEquals("X+5 Y+0.5", reprint("X+5 Y+0.5"))
        }

        @Test
        fun `leading zeros are kept in the lexeme`() {
            // `G01` is the command `G1`: the value is normalised, the rendered lexeme is not.
            assertEquals(listOf(GLetter('G'), GInt(1, "01")), tokens("G01"))
            assertEquals(1, (tokens("G01")[1] as GInt).int)
            assertEquals("G01", reprint("G01"))
            assertEquals("G0001 X007", reprint("G0001 X007"))
        }

        @Test
        fun `the lexeme is part of number identity`() {
            assertNotEquals(GInt(1), GInt(1, "01"))
            assertNotEquals(GFloat("0.5"), GFloat(".5"))
        }

        @Test
        fun `a number with two decimal points is an unknown token`() {
            // GCODE_spec.md section 3.1 allows at most one decimal point; section 9 asks for a
            // lexical error rather than an exception out of the tokenizer.
            assertEquals(listOf(GLetter('X'), GUnknown("1.2.3")), tokens("X1.2.3"))
            assertEquals(listOf(GUnknown("1..2")), tokens("1..2"))
            assertEquals(listOf(GUnknown(".1.2")), tokens(".1.2"))
        }

        @Test
        fun `a malformed number does not stop the rest of the line`() {
            assertEquals(
                listOf(GLetter('X'), GUnknown("1.2.3"), GSpace, GLetter('Y'), GInt(1)),
                tokens("X1.2.3 Y1")
            )
        }

        @Test
        fun `a malformed number round trips`() {
            assertEquals("X1.2.3 Y1", reprint("X1.2.3 Y1"))
            assertEquals(".1.2", reprint(".1.2"))
        }

        @Test
        fun `an integer too large for Int is an unknown token`() {
            assertEquals(listOf(GLetter('N'), GUnknown("99999999999")), tokens("N99999999999"))
            assertEquals("N99999999999", reprint("N99999999999"))
        }

        @Test
        fun `the largest representable integer is still a number`() {
            assertEquals(listOf(GLetter('N'), GInt(2147483647)), tokens("N2147483647"))
        }

        @Test
        fun `a non ascii digit is not a digit`() {
            // GCODE_spec.md section 1.1. Both the predicate and String.toIntOrNull() accept the whole
            // Unicode Nd category, so "١" used to lex as GInt(1, "١").
            assertEquals(listOf(GLetter('X'), GUnknown("١")), tokens("X١"))
        }

        @Test
        fun `a decimal built from non ascii digits is not a number`() {
            // BigDecimal(String) is Unicode-aware as well, so "١.٢" used to lex as GFloat(1.2).
            assertEquals(
                listOf(GLetter('X'), GUnknown("١"), GUnknown("."), GUnknown("٢")),
                tokens("X١.٢")
            )
        }

        @Test
        fun `a non ascii digit does not become part of an ascii number`() {
            assertEquals(listOf(GLetter('X'), GInt(1), GUnknown("١")), tokens("X1١"))
        }

        @Test
        fun `non ascii digits still round trip`() {
            assertEquals("X١.٢", reprint("X١.٢"))
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

        @Test
        fun `a string closed by the last character of the input is terminated`() {
            // The closing quote is the final character, so there is nothing left to look ahead at.
            // This must still be a string and not a lexical error (GCODE_TODO.md 1.7).
            assertEquals(listOf(GLetter('M'), GQuotedString("a")), tokens("M\"a\""))
        }
    }

    /**
     * A literal that never closes is a lexical error, GCODE_spec.md section 9. The token carries the
     * original bytes, opening delimiter included, so `rawText()` still reproduces the input and the
     * degraded text is never silently repaired (GCODE_TODO.md 1.7).
     */
    @Nested
    inner class UnterminatedLiterals {

        @Test
        fun `an unterminated inline comment keeps its opening paren`() {
            assertEquals(listOf(GLetter('M'), GUnknown("(abc")), tokens("M(abc"))
        }

        @Test
        fun `an unterminated inline comment does not repeat its last character`() {
            // The lookahead used to survive the scan and re-emit the final char (GCODE_TODO.md 1.7).
            assertEquals(listOf(GUnknown("(abc")), tokens("(abc"))
        }

        @Test
        fun `an unterminated nested inline comment keeps all of its text`() {
            assertEquals(listOf(GUnknown("(a(b")), tokens("(a(b"))
        }

        @Test
        fun `an inline comment closed one level short is unterminated`() {
            assertEquals(listOf(GUnknown("((a)")), tokens("((a)"))
        }

        @Test
        fun `an unterminated string keeps its opening quote`() {
            assertEquals(listOf(GLetter('M'), GUnknown("\"asd")), tokens("M\"asd"))
        }

        @Test
        fun `a lone quote is a lexical error`() {
            assertEquals(listOf(GUnknown("\"")), tokens("\""))
        }

        @Test
        fun `an unterminated string keeps its doubled quotes verbatim`() {
            assertEquals(listOf(GUnknown("\"a\"\"b")), tokens("\"a\"\"b"))
        }

        @Test
        fun `an unterminated expression keeps its opening brace`() {
            assertEquals(listOf(GLetter('M'), GUnknown("{abc")), tokens("M{abc"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["M(abc", "(abc", "(a(b", "((a)", "M\"asd", "\"", "\"a\"\"b", "M{abc", "{a{b"])
        fun `an unterminated literal round trips`(gcode: String) {
            assertEquals(gcode, reprint(gcode))
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
        fun `tail comment ends at the CR of a CRLF and does not keep it`() {
            // GCODE_spec.md section 1.2: CRLF is a line terminator, so neither of its two characters
            // is comment content. The CR is still emitted - by GLineBreak, not by the comment.
            assertEquals(listOf(GTailComment("ab"), GLineBreak("\r\n")), tokens(";ab\r\n"))
            // GLineBreak.toString() prints only the class name, so pin the bytes too.
            assertEquals(listOf(59, 97, 98, 13, 10), reprint(";ab\r\n").map { it.code })
        }

        @Test
        fun `a tail comment on a CRLF line still round trips`() {
            assertEquals(";ab\r\n", reprint(";ab\r\n"))
        }

        @Test
        fun `a tail comment ended by a lone CR at end of input round trips`() {
            // The lone CR is not a terminator (section 1.2), so it degrades to GUnknown exactly as it
            // does outside a comment - and the byte is still emitted.
            assertEquals(listOf(GTailComment("ab"), GUnknown("\r")), tokens(";ab\r"))
            assertEquals(listOf(59, 97, 98, 13), reprint(";ab\r").map { it.code })
        }

        @Test
        fun `non ascii tail comment is preserved`() {
            assertEquals(listOf(GTailComment("Припарковать оси")), tokens(";Припарковать оси"))
        }
    }

    @Nested
    inner class InlineComments {

        @Test
        fun `the delimiters frame the comment and are not part of its text`() {
            assertEquals(listOf(GInlineComment("feedrate")), tokens("(feedrate)"))
        }

        @Test
        fun `an empty inline comment has empty text`() {
            assertEquals(listOf(GInlineComment("")), tokens("()"))
        }

        @Test
        fun `nested parentheses are part of the comment text`() {
            assertEquals(listOf(GInlineComment("(a)")), tokens("((a))"))
        }

        @Test
        fun `an inline comment between two words round trips`() {
            assertEquals("G1 (feedrate) F1500", reprint("G1 (feedrate) F1500"))
        }

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

        @Test
        fun `a tab is a tab token`() {
            // GCODE_spec.md section 2.1 classifies the tab as whitespace.
            assertEquals(listOf(GTab), tokens("\t"))
        }

        @Test
        fun `a tab separates two words`() {
            assertEquals(
                listOf(GLetter('G'), GInt(1), GTab, GLetter('X'), GInt(1)),
                tokens("G1\tX1")
            )
        }

        @Test
        fun `repeated tabs are separate tokens`() {
            assertEquals(listOf(GTab, GTab), tokens("\t\t"))
        }

        @Test
        fun `tabs and spaces can be mixed`() {
            assertEquals(listOf(GSpace, GTab, GSpace), tokens(" \t "))
        }

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

        @Test
        fun `CRLF is one line break`() {
            assertEquals(listOf(GLineBreak("\r\n")), tokens("\r\n"))
        }

        @Test
        fun `CRLF separates two commands`() {
            assertEquals(
                listOf(GLetter('G'), GInt(1), GLineBreak("\r\n"), GLetter('G'), GInt(2)),
                tokens("G1\r\nG2")
            )
        }

        @Test
        fun `consecutive CRLF breaks are separate tokens`() {
            assertEquals(listOf(GLineBreak("\r\n"), GLineBreak("\r\n")), tokens("\r\n\r\n"))
        }

        @Test
        fun `LF and CRLF can be mixed`() {
            assertEquals(
                listOf(GLineBreak("\n"), GLineBreak("\r\n"), GLineBreak("\n")),
                tokens("\n\r\n\n")
            )
        }

        @Test
        fun `a lone CR is unknown and does not swallow the next character`() {
            assertEquals(
                listOf(GLetter('G'), GInt(1), GUnknown("\r"), GLetter('G'), GInt(2)),
                tokens("G1\rG2")
            )
        }

        @ParameterizedTest
        @ValueSource(chars = ['\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u001F', '\u0085', '\u00A0', '\u2028'])
        fun `only the four separators of section 2 1 are whitespace`(ch: Char) {
            // GCODE_spec.md section 2.1 names space and tab; section 1.2 names LF and CRLF. Everything
            // Char.isWhitespace() adds on top of those four - VT, FF, the file separators, NEL, NBSP,
            // LINE SEPARATOR - is a lexical error (section 9).
            assertEquals(
                listOf(GLetter('G'), GInt(1), GUnknown(ch.toString()), GLetter('X')),
                tokens("G1" + ch + "X")
            )
        }

        @Test
        fun `an ascii space still separates two words`() {
            assertEquals(
                listOf(GLetter('G'), GInt(1), GSpace, GLetter('X'), GInt(1)),
                tokens("G1 X1")
            )
        }

        @Test
        fun `a reclassified non breaking space still round trips`() {
            assertEquals("G1 X", reprint("G1 X"))
        }

        @Test
        fun `a lone CR at end of input is unknown`() {
            assertEquals(listOf(GLetter('G'), GInt(1), GUnknown("\r")), tokens("G1\r"))
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
        fun `a non ascii character does not disturb the surrounding tokens`() {
            assertEquals(
                listOf(GLetter('G'), GInt(1), GUnknown("Я"), GLetter('X'), GInt(2)),
                tokens("G1ЯX2")
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
            val iter = tokenizer.parse(emptyList<Char>().iterator())

            assertFalse(iter.hasNext())
            assertThrows<NoSuchElementException> { iter.next() }
        }

        @Test
        fun `next past the end throws NoSuchElementException whatever the source is`() {
            // A String source used to raise StringIndexOutOfBoundsException where a List source
            // raised NoSuchElementException (GCODE_TODO.md 1.17). Iterator.next() specifies the
            // latter, and the type must not depend on which overload the caller picked.
            assertThrows<NoSuchElementException> { tokenizer.parse("".iterator()).next() }
            assertThrows<NoSuchElementException> { tokenizer.parse(emptyList<Char>().iterator()).next() }
            assertThrows<NoSuchElementException> { tokenizer.parse(emptySequence<Char>().iterator()).next() }
        }

        @Test
        fun `next past the end of a non empty source throws NoSuchElementException`() {
            val iter = tokenizer.parse("G".iterator())

            assertEquals(GLetter('G'), iter.next())
            assertThrows<NoSuchElementException> { iter.next() }
        }
    }

    /**
     * `Iterator.asSequence()` is `constrainOnce()`, so the returned sequence used to be consumable
     * exactly once (GCODE_TODO.md 1.11). A sequence built over a re-iterable source must itself be
     * re-iterable; one built over an iterator cannot be, and that asymmetry is asserted too.
     */
    @Nested
    inner class Reiteration {

        @Test
        fun `a char sequence parse can be consumed twice`() {
            val parsed = tokenizer.parse("G1 X2")

            assertEquals(parsed.toList(), parsed.toList())
            assertEquals(5, parsed.count())
        }

        @Test
        fun `an iterable parse can be consumed twice`() {
            val parsed = tokenizer.parse("G1 X2".toList())

            assertEquals(parsed.toList(), parsed.toList())
        }

        @Test
        fun `a sequence parse is re-iterable when its source is`() {
            val parsed = tokenizer.parse("G1 X2".asSequence())

            assertEquals(parsed.toList(), parsed.toList())
        }

        @Test
        fun `a parse over a bare iterator is single use, by nature of the source`() {
            val parsed = tokenizer.parse("G1 X2".iterator()).asSequence()

            assertEquals(5, parsed.count())
            assertThrows<IllegalStateException> { parsed.toList() }
        }

        @Test
        fun `parseLines can be consumed twice`() {
            val parsed = tokenizer.parseLines(sequenceOf("G28", "M104 S200"))

            assertEquals(parsed.toList(), parsed.toList())
        }
    }

    /**
     * `parseLines` takes lines with their terminators already stripped, as `readLines()` and
     * `lineSequence()` produce them, and puts a terminator back between them. Without it the
     * commands fused into one line (GCODE_TODO.md 1.5).
     */
    @Nested
    inner class ParseLines {

        private fun reprintLines(lines: List<String>, terminator: String = "\n"): String =
            tokenizer.parseLines(lines.asSequence(), terminator).joinToString("") { it.rawText() }

        @Test
        fun `a terminator is re-inserted between lines`() {
            assertEquals("G28\nM104 S200", reprintLines(listOf("G28", "M104 S200")))
        }

        @Test
        fun `the lines stay separate token runs`() {
            assertEquals(
                listOf(
                    GLetter('G'), GInt(28), GLineBreak("\n"),
                    GLetter('M'), GInt(104), GSpace, GLetter('S'), GInt(200)
                ),
                tokenizer.parseLines(sequenceOf("G28", "M104 S200")).toList()
            )
        }

        @Test
        fun `no terminator is appended after the last line`() {
            assertEquals("G28", reprintLines(listOf("G28")))
        }

        @Test
        fun `an empty sequence produces no tokens`() {
            assertEquals(emptyList<GToken>(), tokenizer.parseLines(emptySequence()).toList())
        }

        @Test
        fun `blank lines are preserved`() {
            assertEquals("G28\n\nG90", reprintLines(listOf("G28", "", "G90")))
        }

        @Test
        fun `the terminator is the callers choice`() {
            assertEquals("G28\r\nG90", reprintLines(listOf("G28", "G90"), "\r\n"))
        }

        @Test
        fun `a CRLF terminator is still one line break token`() {
            val breaks = tokenizer.parseLines(sequenceOf("G28", "G90"), "\r\n").filterIsInstance<GLineBreak>()

            assertEquals(listOf(GLineBreak("\r\n")), breaks.toList())
        }

        @Test
        fun `the line count survives the round trip`() {
            val program = listOf("; header", "G28", "G1 X1 F100", "", "G90")
            val lines = GSemanticParser(tokenizer.parseLines(program.asSequence()).iterator())

            assertEquals(program.size, lines.asSequence().count())
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

        // There is no `Stream<Char>` overload: it was the portable core's only java.util.stream
        // dependency, nothing called it, and the four overloads above cover every caller. A JVM
        // caller that wants one writes `parse(stream.asSequence()).asStream()` at its own edge.
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
                "G1 E-5 F1800",
                "G1 X-1.5 Y+2 Z-0.05",
                "X+5 Y+0.5",
                "G01 X007",
                "X.5 Y.40",
                "X1.",
                "G1\tX1\tY2",
                "G92 .1 ;TODO",
                "M851 X0.20 Y.40",
                "M117 \"Hello World!\"",
                "M11 I   \"Hello \"\"World\"\"\"",
                "M140 S{first_layer_bed_temperature[0]}",
                "M{SET_TEMP} F{CURRENT}X1.05",
                "; a whole line comment",
                "G1 (feedrate) F1500",
                "G1 ((nested) comment) F1",
                "()",
                "(a)(b)",
                "G1 F100 ; trailing comment with \" and * and (",
                "N42 G1 X1 F100*9",
                "\n",
                "\n\n",
                "G28\n",
                "G1 X1\nG1 X2\n",
                "\r\n",
                "G28\r\n",
                "G1 X1\r\nG1 X2\r\n",
                "; comment\r\nG1 F100\r\n",
                "G1 X1\nG1 X2\r\nG1 X3",
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
