package org.qw3rtrun.p3d.g.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the primitives the reply decoders scan with, in place of a regex.
 *
 * The decoders' own tests measure what they accept; these measure the three rules underneath, and
 * especially the two that a stdlib call would get wrong: folding is ASCII and nothing else, and a
 * digit is `0`-`9` and nothing else.
 */
class GRsScanTest {

    @Nested
    inner class Trimming {

        @Test
        fun `the edges are blanks and terminators, and nothing else`() {
            assertEquals("ok", " ok".asciiTrim())
            assertEquals("ok", "ok\r\n".asciiTrim())
            assertEquals("ok", "\t ok \r\n".asciiTrim())
            assertEquals("busy: paused for user", " busy: paused for user\n".asciiTrim())
        }

        @Test
        fun `a line of nothing but edges trims to nothing`() {
            assertEquals("", "".asciiTrim())
            assertEquals("", " \t\r\n".asciiTrim())
        }

        /**
         * The deliberate difference from `trim()`, and the reason this exists.
         *
         * `trim()` strips the whole Unicode space category - a form feed, a vertical tab, a
         * non-breaking space, U+2028 LINE SEPARATOR. Spec 1.1's wire is 7-bit and spec 2.1 names two
         * separators; spec 1.2 names the terminators. A byte outside that set is **content** here,
         * and a port written against four named characters reproduces this where one written against
         * "whitespace" would not.
         */
        @Test
        fun `other Unicode spaces are content, not edges`() {
            // U+000C form feed, U+00A0 no-break space, U+2028 line separator: trim() strips
            // all three, and none of them is an edge here - they survive as content.
            assertEquals("\u000Cok\u00A0", "\u000Cok\u00A0".asciiTrim())
            assertEquals("\u2028ok", " \u2028ok\r\n".asciiTrim())

            // Which is the point at which this stops agreeing with the stdlib - stated rather
            // than implied, because it is the whole reason the helper exists.
            assertEquals("ok", "\u000Cok\u00A0".trim())
        }

        @Test
        fun `a keyword line is recognised without allocating a trim`() {
            assertTrue(" OK\r\n".equalsAscii("ok"))
            assertTrue("\twait ".equalsAscii("WAIT"))
            assertTrue("Not SD printing".equalsAscii("not sd printing"))

            assertFalse("ok P15 B3".equalsAscii("ok"))
            assertFalse("".equalsAscii("ok"))
            assertFalse("okay".equalsAscii("ok"))
        }

        @Test
        fun `two cut fields compare by ASCII case alone`() {
            assertTrue("TRIGGERED".sameAscii("triggered"))
            assertTrue("true".sameAscii("TRUE"))

            assertFalse("open".sameAscii("TRIGGERED"))
            assertFalse("ok".sameAscii("ok "))
            // U+212A again: not an ASCII letter, so not folded.
            assertFalse("Ko".sameAscii("ko"))
        }
    }

    @Nested
    inner class StartsWithAscii {

        @Test
        fun `a prefix matches in either case`() {
            assertTrue("Resend: 12".startsWithAscii("resend"))
            assertTrue("resend: 12".startsWithAscii("RESEND"))
            assertTrue("SD printing byte 1/2".startsWithAscii("sd printing byte"))
        }

        @Test
        fun `it matches at an offset`() {
            assertTrue("ok Last Line: 7".startsWithAscii("last line", 3))
            assertFalse("ok Last Line: 7".startsWithAscii("last line", 2))
        }

        @Test
        fun `a prefix longer than what is left does not match and does not throw`() {
            assertFalse("rs".startsWithAscii("resend"))
            assertFalse("resend".startsWithAscii("resend", 1))
            assertFalse("resend".startsWithAscii("r", -1))
        }

        @Test
        fun `folding is ASCII, so a Unicode letter that lowercases to one does not match`() {
            // U+212A KELVIN SIGN lowercases to `k` under a Unicode fold, and the wire is 7-bit.
            assertFalse("Ko".startsWithAscii("ko"))
            // A Turkish locale folds `i`/`I` differently; this does not consult one.
            assertTrue("I".startsWithAscii("i"))
        }

        @Test
        fun `an empty prefix matches anywhere inside the line`() {
            assertTrue("busy".startsWithAscii("", 2))
            assertFalse("busy".startsWithAscii("", 5))
        }
    }

    @Nested
    inner class BlanksEnd {

        @Test
        fun `it skips spaces and tabs only`() {
            assertEquals(3, "a  b".blanksEnd(1))
            assertEquals(2, "a\tb".blanksEnd(1))
            assertEquals(1, "a\nb".blanksEnd(1))
        }

        @Test
        fun `no blanks means the index is unchanged, and the end is a fixed point`() {
            assertEquals(0, "ok".blanksEnd(0))
            assertEquals(2, "ok".blanksEnd(2))
        }
    }

    @Nested
    inner class DigitsEnd {

        @Test
        fun `it runs to the first non-digit`() {
            assertEquals(2, "12ab".digitsEnd(0))
            assertEquals(4, "ab12".digitsEnd(2))
            assertEquals(4, "1234".digitsEnd(0))
        }

        @Test
        fun `no digits means the run is empty, which is how a caller rejects a line`() {
            assertEquals(0, "ab".digitsEnd(0))
            assertEquals(2, "ab".digitsEnd(2))
        }

        @Test
        fun `only ASCII digits count`() {
            // U+0661 ARABIC-INDIC DIGIT ONE is `Char.isDigit()`, and is not a wire digit.
            assertEquals(0, "١".digitsEnd(0))
            assertEquals(1, "1١".digitsEnd(0))
        }
    }
}
