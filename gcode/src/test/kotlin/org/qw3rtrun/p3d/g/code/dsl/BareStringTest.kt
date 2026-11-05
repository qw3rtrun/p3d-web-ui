package org.qw3rtrun.p3d.g.code.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.block.GBlockReader
import org.qw3rtrun.p3d.g.code.core.block.GFramedBlock
import org.qw3rtrun.p3d.g.code.core.command.GCommand
import org.qw3rtrun.p3d.g.marlin.command.GCodeMacrosM810
import org.qw3rtrun.p3d.g.marlin.command.SelectSDFile

/**
 * What [bareString] will and will not carry.
 *
 * A bare rest-of-line string (spec 3.4a) is the one value a caller can use to write a *different*
 * command than the one it asked for: it is undelimited, it is usually a filename or a message
 * somebody typed, and `GEncoder.frame` puts it on the wire verbatim. These tests state the four
 * characters that are refused, the two things that are emphatically still accepted - spaces, and a
 * quoted string where the command takes one - and what the refusal says.
 *
 * Length is not tested here because it is not this function's rule: it depends on the whole block
 * and on the line number the transport will prepend, and `GLineLimit.payloadBudget` is what answers
 * it for the caller.
 */
class BareStringTest {

    private fun enc(c: GCommand) = GEncoder.encode(c)

    @Nested
    inner class Accepted {

        /**
         * Spec 3.4a: the string is not delimited and *may contain spaces*, which is the whole point
         * of it - a long filename and an `M117` message are both mostly spaces, and a guard that
         * refused them would have made the commands that carry one unwritable.
         */
        @Test
        fun `a name with spaces is a name, not a mistake`() {
            assertEquals("M23 A LONG FILE NAME.GCO", enc(M(23, bareString("A LONG FILE NAME.GCO"))))
            assertEquals("M117 Hello World", enc(M(117, bareString("Hello World"))))
            assertEquals("M117 Printing 42% - 1h 20m left", enc(M(117, bareString("Printing 42% - 1h 20m left"))))
        }

        /**
         * The rule is four characters, not a whitelist: every other printable byte spec 1.1 allows
         * goes through, punctuation and quotes included.
         */
        @Test
        fun `every other printable ASCII character goes through`() {
            val printable = StringBuilder()
            for (code in 0x20..0x7E) {
                val c = code.toChar()
                if (c == ';' || c == '*') continue
                printable.append(c)
            }
            val value = printable.toString()

            assertEquals("M117 $value", enc(M(117, bareString(value))))
        }

        /**
         * The other half of the claim: what is accepted still frames into a block the reader
         * verifies, so the check has not been bought by emitting bytes the parser rejects.
         */
        @Test
        fun `what is accepted still frames and parses back as a verified block`() {
            val framed = GEncoder.frame(1, M(23, bareString("A LONG FILE NAME.GCO")).block())

            assertTrue(framed.startsWith("N1 M23 A LONG FILE NAME.GCO*")) { framed }
            assertInstanceOf(GFramedBlock::class.java, GBlockReader.blocks(framed).first()) { framed }
        }
    }

    @Nested
    inner class Refused {

        /**
         * The one that matters. Spec 1.2: a line terminator ends the block, so `M30 a.gco\nM112` is
         * a delete *and* an emergency stop - and the transport frames what it is handed without
         * parsing it, leaving the `N` word on one side of the break and the checksum on the other.
         */
        @Test
        fun `a line terminator would split the block`() {
            assertThrows(IllegalArgumentException::class.java) { bareString("a.gco\nM112") }
            assertThrows(IllegalArgumentException::class.java) { bareString("a.gco\r\nM112") }
            assertThrows(IllegalArgumentException::class.java) { bareString("a.gco\rM112") }
        }

        /** Spec 3.4a and 6: `;` runs to the end of the line, so it swallows the checksum behind it. */
        @Test
        fun `a semicolon would comment out the checksum`() {
            assertThrows(IllegalArgumentException::class.java) { bareString("a.gco;M112") }
            assertThrows(IllegalArgumentException::class.java) { bareString("note; see below") }
        }

        /** Spec 3.4a and 8.3: `*` is the checksum delimiter, so the rest of the name is read as one. */
        @Test
        fun `a star would be read as the checksum delimiter`() {
            assertThrows(IllegalArgumentException::class.java) { bareString("a.gco*42") }
        }

        /**
         * Spec 1.1: the portable wire is `0x20`-`0x7E`. That covers the accented letter a browser
         * will happily post, the control characters below space, and each half of a surrogate pair.
         */
        @Test
        fun `anything outside printable ASCII goes too`() {
            assertThrows(IllegalArgumentException::class.java) { bareString("café.gco") }
            assertThrows(IllegalArgumentException::class.java) { bareString("a\tb.gco") }
            assertThrows(IllegalArgumentException::class.java) { bareString("a\u0000b.gco") }
            assertThrows(IllegalArgumentException::class.java) { bareString("a\u007F.gco") }
            assertThrows(IllegalArgumentException::class.java) { bareString("print 😀.gco") }
        }

        /**
         * The refusal has to be actionable: a caller sees a name it did not write by hand, so the
         * message names the character, says where it is, and does not stop there.
         */
        @Test
        fun `the message names the character and its position`() {
            val semicolon = assertThrows(IllegalArgumentException::class.java) { bareString("a.gco;M112") }
            assertTrue(semicolon.message!!.contains("';'")) { semicolon.message!! }
            assertTrue(semicolon.message!!.contains("position 5")) { semicolon.message!! }

            val star = assertThrows(IllegalArgumentException::class.java) { bareString("a.gco*42") }
            assertTrue(star.message!!.contains("'*'")) { star.message!! }
            assertTrue(star.message!!.contains("position 5")) { star.message!! }
        }

        /**
         * A character that cannot be printed is named by its code point rather than pasted into the
         * message, where a raw newline would break the message across two lines of the log and an
         * invisible one would say nothing at all.
         */
        @Test
        fun `an unprintable character is named by its code point`() {
            val newline = assertThrows(IllegalArgumentException::class.java) { bareString("a.gco\nM112") }
            assertTrue(newline.message!!.contains("U+000A")) { newline.message!! }
            assertTrue(newline.message!!.contains("position 5")) { newline.message!! }
            assertTrue(!newline.message!!.contains("\n")) { newline.message!! }

            val accent = assertThrows(IllegalArgumentException::class.java) { bareString("café.gco") }
            assertTrue(accent.message!!.contains("U+00E9")) { accent.message!! }
            assertTrue(accent.message!!.contains("position 3")) { accent.message!! }
        }

        /**
         * And it points at the way out, because a refused name is not necessarily an unsendable one
         * - spec 3.4b quoted strings are delimited and compose with other parameters.
         */
        @Test
        fun `the message points at the quoted alternative`() {
            val e = assertThrows(IllegalArgumentException::class.java) { bareString("a.gco;M112") }

            assertTrue(e.message!!.contains("text(")) { e.message!! }
            assertTrue(e.message!!.contains("3.4b")) { e.message!! }
        }
    }

    @Nested
    inner class TheQuotedAlternative {

        /**
         * Spec 3.4b: a quoted string is delimited, so the characters a bare string cannot hold are
         * ordinary content inside one - the tokenizer reads to the closing quote and never sees a
         * comment or a checksum field. `M550` is the pair in one command: `P"name"` is quoted,
         * everything after `M550` is bare.
         */
        @Test
        fun `a quoted parameter carries what a bare string cannot`() {
            assertEquals("M550 P\"my;printer\"", enc(M(550, P(text("my;printer")))))
            assertEquals("M550 P\"a*b\"", enc(M(550, P(text("a*b")))))

            assertThrows(IllegalArgumentException::class.java) { M(550, bareString("my;printer")) }
        }

        /** And the quoted form still frames into a block the reader verifies. */
        @Test
        fun `the quoted form frames and parses back as a verified block`() {
            val framed = GEncoder.frame(1, M(550, P(text("my;printer"))).block())

            assertTrue(framed.startsWith("N1 M550 P\"my;printer\"*")) { framed }
            assertInstanceOf(GFramedBlock::class.java, GBlockReader.blocks(framed).first()) { framed }
        }
    }

    @Nested
    inner class WhatTheGeneratedCommandsSee {

        /** The commands this is really about: a filename straight from a browser. */
        @Test
        fun `a filename that would change the block is refused where it is built`() {
            assertEquals("M23 BENCHY.GCO", enc(SelectSDFile("BENCHY.GCO").encode()))
            assertThrows(IllegalArgumentException::class.java) { SelectSDFile("a.gco\nM112").encode() }
            assertThrows(IllegalArgumentException::class.java) { SelectSDFile("a.gco;M112").encode() }
        }

        /**
         * `M810`-`M819` store G-code, which legitimately carries `;` comments - but the macro text
         * is a bare rest-of-line string like any other, and has to survive the *block* first:
         * `M810 G1 X1 ; move` over a numbered link is a block whose comment swallows the checksum
         * behind it, so the firmware never stores the macro and asks for a resend that will never be
         * right. Marlin's own multi-statement example uses `|`, not a comment or a newline -
         * `M815 G0 X0 Y0|G0 Z10|M300 S440 P50` - so a caller never legitimately needs one here.
         */
        @Test
        fun `a stored macro is G-code, and a comment in one is still refused`() {
            assertEquals(
                "M810 G0 X0 Y0|G0 Z10|M300 S440 P50",
                enc(GCodeMacrosM810("G0 X0 Y0|G0 Z10|M300 S440 P50").encode()),
            )
            assertThrows(IllegalArgumentException::class.java) {
                GCodeMacrosM810("G1 X1 ; move").encode()
            }
        }
    }
}
