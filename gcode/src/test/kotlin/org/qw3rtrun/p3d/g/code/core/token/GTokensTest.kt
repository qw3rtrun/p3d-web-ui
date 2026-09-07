package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit tests for the token model itself - `rawText()` fidelity, the sealed hierarchy membership that
 * the parser layer relies on, and the `toToken()` conversions.
 *
 * See GCODE_spec.md section 2 (lexical structure) and Appendix B.1 for the spec-to-type mapping.
 */
class GTokensTest {

    @Nested
    inner class Identifiers {

        @Test
        fun `letter exposes itself as name and raw text`() {
            val letter = GLetter('X')

            assertEquals("X", letter.name)
            assertEquals("X", letter.rawText())
        }

        @Test
        fun `letter preserves case`() {
            assertEquals("g", GLetter('g').rawText())
            assertEquals("G", GLetter('G').rawText())
            assertNotEquals(GLetter('g'), GLetter('G'))
        }

        @Test
        fun `checksum marker is an identifier named star`() {
            assertEquals("*", GChecksum.name)
            assertEquals("*", GChecksum.rawText())
        }

        @Test
        fun `checksum marker is a singleton`() {
            assertSame(GChecksum, GChecksum)
            assertEquals(GChecksum, GChecksum)
        }
    }

    @Nested
    inner class Numbers {

        @Test
        fun `int exposes value as number and raw text`() {
            val int = GInt(42)

            assertEquals(42, int.int)
            assertEquals(42, int.number)
            assertEquals("42", int.rawText())
        }

        @Test
        fun `int renders zero and negative values`() {
            assertEquals("0", GInt(0).rawText())
            assertEquals("-7", GInt(-7).rawText())
        }

        @Test
        fun `int renders extreme values`() {
            assertEquals("2147483647", GInt(Int.MAX_VALUE).rawText())
            assertEquals("-2147483648", GInt(Int.MIN_VALUE).rawText())
        }

        @Test
        fun `float exposes big decimal as number and raw text`() {
            val float = GFloat(BigDecimal("10.5"))

            assertEquals(BigDecimal("10.5"), float.float)
            assertEquals(BigDecimal("10.5"), float.number)
            assertEquals("10.5", float.rawText())
        }

        @Test
        fun `float keeps the scale it was given`() {
            assertEquals("1.050", GFloat("1.050").rawText())
            assertEquals("1.05", GFloat("1.05").rawText())
            assertEquals("0.500", GFloat(BigDecimal("0.500")).rawText())
        }

        @Test
        fun `float can be built from an int`() {
            assertEquals(GFloat(BigDecimal(3)), GFloat(3))
            assertEquals("3", GFloat(3).rawText())
        }

        @Test
        fun `int carries the lexeme it was parsed from`() {
            val int = GInt(1, "01")

            assertEquals(1, int.int)
            assertEquals("01", int.rawText())
        }

        @Test
        fun `int defaults its lexeme to the canonical form`() {
            assertEquals("1", GInt(1).lexeme)
            assertEquals("-7", GInt(-7).lexeme)
        }

        @Test
        fun `int equality is lexeme sensitive`() {
            // Same value, different lexeme: two tokens, so that rawText() round-trips what was read.
            assertNotEquals(GInt(1), GInt(1, "01"))
            assertNotEquals(GInt(5), GInt(5, "+5"))
            assertEquals(GInt(1), GInt(1, "1"))
        }

        @Test
        fun `float carries the lexeme it was parsed from`() {
            val float = GFloat(BigDecimal("0.5"), ".5")

            assertEquals(0, float.float.compareTo(BigDecimal("0.5")))
            assertEquals(".5", float.rawText())
        }

        @Test
        fun `float built from a string keeps that string as its lexeme`() {
            assertEquals(".5", GFloat(".5").rawText())
            assertEquals("1.", GFloat("1.").rawText())
            assertEquals("+1.5", GFloat("+1.5").rawText())
        }

        @Test
        fun `float from a double does not expand the binary representation`() {
            // BigDecimal(1.05) is 1.0500000000000000444...; BigDecimal.valueOf(1.05) is 1.05.
            assertEquals("1.05", GFloat(1.05).rawText())
            assertEquals("1.05", 1.05.toToken().rawText())
            assertEquals("-0.1", GFloat(-0.1).rawText())
            assertEquals("0.0", GFloat(0.0).rawText())
        }

        @Test
        fun `float equality is scale sensitive`() {
            // Intentional: GFloat keeps BigDecimal semantics so that rawText() round-trips the exact
            // digits that were parsed. 1.0 and 1.00 are different lexemes, hence different tokens.
            assertNotEquals(GFloat("1.0"), GFloat("1.00"))
            assertEquals(0, GFloat("1.0").float.compareTo(GFloat("1.00").float))
        }
    }

    @Nested
    inner class Strings {

        @Test
        fun `quoted string is rendered with surrounding quotes`() {
            assertEquals("\"abc\"", GQuotedString("abc").rawText())
        }

        @Test
        fun `empty quoted string is rendered as two quotes`() {
            assertEquals("\"\"", GQuotedString("").rawText())
        }

        @Test
        fun `embedded quotes are doubled on render`() {
            // GCODE_spec.md section 3.4 - the RepRapFirmware escaping rule.
            assertEquals("\"say \"\"hi\"\"\"", GQuotedString("say \"hi\"").rawText())
            assertEquals("\"\"\"\"", GQuotedString("\"").rawText())
        }

        @Test
        fun `quoted string keeps spaces separators and non ascii content`() {
            assertEquals("\"a b\tc\"", GQuotedString("a b\tc").rawText())
            assertEquals("\"; not a comment\"", GQuotedString("; not a comment").rawText())
            assertEquals("\"Привет\"", GQuotedString("Привет").rawText())
        }
    }

    @Nested
    inner class Comments {

        @Test
        fun `tail comment is rendered with its key`() {
            assertEquals("; move", GTailComment(" move").rawText())
            assertEquals(";", GTailComment("").rawText())
        }

        @Test
        fun `tail comment key is configurable`() {
            assertEquals("//x", GTailComment("x", "//").rawText())
        }

        @Test
        fun `inline comment is rendered with its delimiters`() {
            assertEquals("(msg)", GInlineComment("msg").rawText())
            assertEquals("()", GInlineComment("").rawText())
        }

        @Test
        fun `inline comment delimiters are configurable`() {
            assertEquals("[msg]", GInlineComment("msg", "[", "]").rawText())
        }

        @Test
        fun `comment text is exposed through the common string property`() {
            val comments: List<GComment> = listOf(GTailComment("a"), GInlineComment("b"))

            assertEquals(listOf("a", "b"), comments.map { it.string })
        }
    }

    @Nested
    inner class Separators {

        @Test
        fun `space and tab render as their character`() {
            assertEquals(" ", GSpace.rawText())
            assertEquals(' ', GSpace.char)
            assertEquals("\t", GTab.rawText())
            assertEquals('\t', GTab.char)
        }

        @Test
        fun `whitespace tokens are singletons`() {
            val space: GToken = GSpace
            val tab: GToken = GTab

            assertSame(GSpace, space)
            assertSame(GTab, tab)
            assertNotEquals(space, tab)
        }

        @Test
        fun `line break defaults to LF and keeps a custom breaker`() {
            assertEquals("\n", GLineBreak().rawText())
            assertEquals("\r\n", GLineBreak("\r\n").rawText())
            assertEquals(GLineBreak("\n"), GLineBreak())
            assertNotEquals(GLineBreak("\r\n"), GLineBreak("\n"))
        }
    }

    @Nested
    inner class Unknown {

        @Test
        fun `unknown token renders the original text`() {
            assertEquals("?", GUnknown("?").rawText())
            assertEquals("~", GUnknown('~').rawText())
        }

        @Test
        fun `char and string constructors agree`() {
            assertEquals(GUnknown("?"), GUnknown('?'))
        }

        @Test
        fun `unknown token can hold more than one character`() {
            assertEquals("abc", GUnknown("abc").rawText())
        }
    }

    @Nested
    inner class Expressions {

        @Test
        fun `raw expression renders its source text verbatim`() {
            val expression = GRawExpression("{move.axes[0].max}")

            assertEquals("{move.axes[0].max}", expression.rawText())
            assertEquals("{move.axes[0].max}", expression.exception)
        }
    }

    @Nested
    inner class Hierarchy {

        @Test
        fun `values and identifiers are elements`() {
            val elements: List<GToken> = listOf(
                GLetter('X'),
                GChecksum,
                GInt(1),
                GFloat("1.5"),
                GQuotedString("s"),
                GRawExpression("{e}"),
                GUnknown("?")
            )

            assertTrue(elements.all { it is GElement }) { "expected all of $elements to be GElement" }
        }

        @Test
        fun `comments and separators are not elements`() {
            // GCommandParser filters the line down to GElement, so comments and whitespace must stay
            // outside that hierarchy.
            val nonElements: List<GToken> = listOf(
                GTailComment("c"),
                GInlineComment("c"),
                GSpace,
                GTab,
                GLineBreak()
            )

            assertTrue(nonElements.none { it is GElement }) { "expected none of $nonElements to be GElement" }
        }

        @Test
        fun `numbers and strings are literals`() {
            val literals: List<GToken> = listOf(GInt(1), GFloat("1.0"), GQuotedString("s"))

            assertTrue(literals.all { it is GLiteral }) { "expected all of $literals to be GLiteral" }
            assertEquals(2, literals.count { it is GNumber })
            assertEquals(1, literals.count { it is GString })
        }

        @Test
        fun `letters and the checksum marker are identifiers`() {
            val identifiers: List<GToken> = listOf(GLetter('X'), GChecksum)

            assertTrue(identifiers.all { it is GIdentifier }) { "expected all of $identifiers to be GIdentifier" }
        }

        @Test
        fun `identifiers are not literals and literals are not identifiers`() {
            val letter: GToken = GLetter('X')
            val int: GToken = GInt(1)
            val string: GToken = GQuotedString("s")

            assertFalse(letter is GLiteral)
            assertFalse(int is GIdentifier)
            assertFalse(string is GIdentifier)
        }

        @Test
        fun `separators are separators and whitespace`() {
            val space: GToken = GSpace
            val tab: GToken = GTab
            val lineBreak: GToken = GLineBreak()

            assertTrue(space is GWhitespace)
            assertTrue(tab is GWhitespace)
            assertTrue(space is GSeparator)
            assertTrue(lineBreak is GSeparator)
            assertFalse(lineBreak is GWhitespace)
        }
    }

    @Nested
    inner class Sequences {

        @Test
        fun `toSeq wraps a token in a single element sequence`() {
            assertEquals(listOf<GToken>(GSpace), GSpace.toSeq().toList())
            assertEquals(listOf<GToken>(GTailComment("c")), GTailComment("c").toSeq().toList())
        }

        @Test
        fun `toSeq of an element is a sequence of elements`() {
            val elements: Sequence<GElement> = GLetter('X').toSeq()

            assertEquals(listOf(GLetter('X')), elements.toList())
        }
    }

    @Nested
    inner class Conversions {

        @Test
        fun `int converts to an int token`() {
            assertEquals(GInt(42), 42.toToken())
        }

        @Test
        fun `big decimal converts to a float token keeping its scale`() {
            assertEquals(GFloat(BigDecimal("2.50")), BigDecimal("2.50").toToken())
            assertEquals("2.50", BigDecimal("2.50").toToken().rawText())
        }

        @Test
        fun `char converts to a letter token`() {
            assertEquals(GLetter('X'), 'X'.toToken())
        }

        @Test
        fun `string converts to a quoted string token`() {
            assertEquals(GQuotedString("hi"), "hi".toToken())
            assertEquals("\"hi\"", "hi".toToken().rawText())
        }
    }
}
