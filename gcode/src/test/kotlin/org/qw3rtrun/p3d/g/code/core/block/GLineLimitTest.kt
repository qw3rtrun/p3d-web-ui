package org.qw3rtrun.p3d.g.code.core.block

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.command.GCommand
import org.qw3rtrun.p3d.g.code.core.command.GUnnamedStr
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GLetter
import org.qw3rtrun.p3d.g.code.core.token.GUnquotedString

/**
 * Spec 1.3, measured.
 *
 * The claims worth testing are the two the arithmetic gets wrong when it is written casually: what
 * counts as part of the line, and how much of the buffer the framing has already spent before any
 * payload goes in.
 */
class GLineLimitTest {

    @Nested
    inner class WhatCounts {

        @Test
        fun `a terminator is not in the buffer`() {
            // Spec 1.3 measures 96 bytes "excluding the terminator"; spec 1.2 makes it LF or CRLF.
            assertEquals(3, GLineLimit.wireLength("G28"))
            assertEquals(3, GLineLimit.wireLength("G28\n"))
            assertEquals(3, GLineLimit.wireLength("G28\r\n"))
        }

        @Test
        fun `only one terminator comes off`() {
            // Two of them is two lines, and answering for it as one line would be answering a
            // question nobody asked.
            assertEquals(4, GLineLimit.wireLength("G28\n\n"))
            assertEquals(5, GLineLimit.wireLength("G28\r\n\r\n"))
        }

        @Test
        fun `a lone CR is content, because spec 1_2 says it is not a terminator`() {
            // A CR not followed by LF is a stray character to a strict parser - the tokenizer makes
            // it a GUnknown - so it is a byte in the buffer like any other.
            assertEquals(4, GLineLimit.wireLength("G28\r"))
        }

        @Test
        fun `a non-ASCII character costs more than one byte`() {
            // The case this function exists for: M117 carries a rest-of-line string (spec 3.4a) and
            // a user types into it. Counting characters would pass a line the firmware truncates.
            assertEquals(10, GLineLimit.wireLength("M117 Café"))
            assertEquals(9, "M117 Café".length)

            // Three bytes above U+07FF, four for a surrogate pair - counted once, not twice.
            assertEquals(3, GLineLimit.wireLength("€"))
            assertEquals(4, GLineLimit.wireLength("😀"))
        }

        @Test
        fun `an empty line occupies nothing`() {
            assertEquals(0, GLineLimit.wireLength(""))
            assertEquals(0, GLineLimit.wireLength("\n"))
            assertTrue(GLineLimit.fits(""))
        }
    }

    @Nested
    inner class Fitting {

        @Test
        fun `a line at exactly the limit fits, and one byte more does not`() {
            assertTrue(GLineLimit.fits("X".repeat(96)))
            assertFalse(GLineLimit.fits("X".repeat(97)))
            assertEquals(0, GLineLimit.overrun("X".repeat(96)))
            assertEquals(1, GLineLimit.overrun("X".repeat(97)))
        }

        @Test
        fun `the overrun is how much has to come out`() {
            assertEquals(4, GLineLimit.overrun("X".repeat(100)))
            assertEquals(27, GLineLimit.overrun("N1 " + "X".repeat(120)))
            assertEquals(0, GLineLimit.overrun("G28"))
        }

        @Test
        fun `the limit is the dialect's, and Marlin's is the smallest`() {
            val line = "X".repeat(200)
            assertFalse(GLineLimit.fits(line, GLineLimit.MARLIN))
            assertTrue(GLineLimit.fits(line, GLineLimit.REPRAP_FIRMWARE))
            assertTrue(GLineLimit.fits(line, GLineLimit.RS274))
        }
    }

    @Nested
    inner class Budget {

        @Test
        fun `the framing is spent before any payload goes in`() {
            // `N1 ` is three bytes and `*nnn` is up to four: 96 - 7.
            assertEquals(89, GLineLimit.payloadBudget(1))
            assertEquals(87, GLineLimit.payloadBudget(1, checksumDigits = GLineLimit.CRC16_DIGITS))
        }

        @Test
        fun `the budget shrinks as the line number grows`() {
            // The part that cannot be precomputed, and the reason this takes a number at all.
            assertEquals(89, GLineLimit.payloadBudget(1))
            assertEquals(88, GLineLimit.payloadBudget(10))
            assertEquals(84, GLineLimit.payloadBudget(100000))
            assertTrue(GLineLimit.payloadBudget(1) > GLineLimit.payloadBudget(Int.MAX_VALUE))
        }

        @Test
        fun `a budget is never negative`() {
            // So a caller may compare a length against it without a second guard.
            assertEquals(0, GLineLimit.payloadBudget(1, limit = 4))
            assertEquals(0, GLineLimit.payloadBudget(1, limit = 0))
        }

        @Test
        fun `spec 1_3's conservative figure is below the exact one, as it should be`() {
            // 76 is for a generator that does not know the number yet; the budget is for one that
            // does. The guidance being the safer of the two is what makes it usable as guidance.
            assertTrue(GLineLimit.INTEROPERABLE_PAYLOAD < GLineLimit.payloadBudget(1))
            assertTrue(GLineLimit.INTEROPERABLE_PAYLOAD < GLineLimit.payloadBudget(Int.MAX_VALUE))
        }

        /**
         * The budget and the framer have to agree, or the number is decoration. Checked against
         * `GEncoder.frame` itself rather than against a second copy of its format.
         */
        @Test
        fun `a payload at the budget frames to exactly the limit`() {
            for (number in listOf(1, 9, 10, 99, 100, 1000, 100000)) {
                val budget = GLineLimit.payloadBudget(number)
                val framed = GEncoder.frame(number, "X".repeat(budget))

                assertTrue(GLineLimit.fits(framed)) {
                    "N$number with a $budget-byte payload framed to ${GLineLimit.wireLength(framed)}: $framed"
                }

                // Deliberately not asserted: that one byte more does *not* fit. The budget assumes
                // the widest `*` field the algorithm can write, and a particular checksum is often
                // narrower - `*7` where `*213` was budgeted for - so a payload over the budget can
                // still fit this one line. The budget is what is safe for every block, which is a
                // stronger claim than what is true of one.
            }
        }

        @Test
        fun `a payload past the budget is what the widest checksum would not fit`() {
            // The budget's actual promise, stated the way it is meant: at the widest `*` field the
            // algorithm can produce, one byte past the budget is one byte past the limit.
            for (number in listOf(1, 10, 100000)) {
                val budget = GLineLimit.payloadBudget(number)
                val widest = 3 + number.toString().length + GLineLimit.XOR_DIGITS
                assertEquals(GLineLimit.MARLIN, budget + widest) {
                    "the budget for N$number does not account for the framing exactly"
                }
            }
        }

        @Test
        fun `a real over-long block is the one a resend cannot repair`() {
            // The message is 90 bytes, so M117 plus a space plus the message is 95 - inside the
            // limit bare, and over it once numbered and checksummed. This is the shape that puts a
            // host into a resend loop: the replay is byte-identical, so it is truncated identically.
            val message = "T".repeat(90)
            val command = GCommand(
                GLetter('M'), GInt(117),
                listOf(GUnnamedStr(GUnquotedString(message))),
            )

            val bare = GEncoder.encode(command)
            assertEquals(95, GLineLimit.wireLength(bare))
            assertTrue(GLineLimit.fits(bare))

            val framed = GEncoder.frame(1, command)
            assertFalse(GLineLimit.fits(framed)) { "framed to ${GLineLimit.wireLength(framed)}: $framed" }
            assertTrue(message.length > GLineLimit.payloadBudget(1))
        }
    }
}
