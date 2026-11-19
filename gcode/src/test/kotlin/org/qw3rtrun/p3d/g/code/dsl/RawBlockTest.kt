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
import org.qw3rtrun.p3d.g.code.core.block.GLineLimit

/**
 * What [rawBlock] will and will not carry.
 *
 * A whole command line typed into a terminal is the one wire-bound string nothing in this module
 * built: the operator wrote it, it is not parsed, and `GEncoder.frame` puts it on the wire verbatim.
 * These tests state the three refusals - blank, the four characters [bareString] also refuses, and
 * spec 1.3's 76-byte interoperable payload - the two things that are emphatically still accepted -
 * spaces, and a command no firmware has - and what each refusal says.
 */
class RawBlockTest {

    @Nested
    inner class Accepted {

        /** The trimmed form is what callers send, so trimming is part of the return value. */
        @Test
        fun `a typed line comes back trimmed`() {
            assertEquals("M105", rawBlock("M105"))
            assertEquals("M105", rawBlock("  M105  "))
            assertEquals("G28 X Y", rawBlock("\tG28 X Y "))
        }

        /**
         * A terminator *around* the line is the textarea's, not the operator's, and comes off with
         * the rest of the whitespace - while one *inside* the line is a second command and is
         * refused by the character rule below.
         */
        @Test
        fun `a trailing terminator is trimmed rather than refused`() {
            assertEquals("M105", rawBlock("M105\n"))
            assertEquals("M105", rawBlock("M105\r\n"))
        }

        /**
         * Spec 3.4a: a rest-of-line argument is mostly spaces, and a gate that refused them would
         * make `M117` and `M23` untypable - which is the way this can be over-tightened.
         */
        @Test
        fun `spaces inside the line survive`() {
            assertEquals("M117 Hello World", rawBlock("M117 Hello World"))
            assertEquals("M23 A LONG FILE NAME.GCO", rawBlock("M23 A LONG FILE NAME.GCO"))
            assertEquals("M117 Printing 42% - 1h 20m left", rawBlock("M117 Printing 42% - 1h 20m left"))
        }

        /**
         * The rule is four characters, not a whitelist: every other printable byte spec 1.1 allows
         * goes through. Split across two lines because all 95 at once is over spec 1.3's budget, and
         * this is the character rule's test, not the length rule's.
         */
        @Test
        fun `every other printable ASCII character goes through`() {
            val first = StringBuilder()
            val second = StringBuilder()
            for (code in 0x20..0x7E) {
                val c = code.toChar()
                if (c == ';' || c == '*') continue
                if (code <= 0x5F) first.append(c) else second.append(c)
            }

            assertEquals(first.toString().trim(), rawBlock(first.toString()))
            assertEquals(second.toString(), rawBlock(second.toString()))
        }

        /**
         * The control that pins what this is *not*: it does not validate G-code. A terminal exists
         * to explore a printer, and `echo:Unknown command: "M851"` from the machine is a better
         * answer than a refusal from a host that holds no command table.
         */
        @Test
        fun `a command no firmware has is still a line`() {
            assertEquals("M851", rawBlock("M851"))
            assertEquals("NOTGCODE", rawBlock("NOTGCODE"))
            assertEquals("banana", rawBlock("banana"))
        }

        /**
         * The other half of the claim: what is accepted frames into a block the reader verifies, so
         * the gate has not been bought by passing bytes the parser rejects.
         */
        @Test
        fun `what is accepted frames and parses back as a verified block`() {
            val framed = GEncoder.frame(1, rawBlock("  M117 Hello World  "))

            assertEquals("N1 M117 Hello World*37", framed)
            assertInstanceOf(GFramedBlock::class.java, GBlockReader.blocks(framed).first()) { framed }
        }
    }

    @Nested
    inner class Refused {

        /** Nothing typed is no block to send, and an empty `N<n> *<checksum>` is not one either. */
        @Test
        fun `a blank line is refused`() {
            val empty = assertThrows(IllegalArgumentException::class.java) { rawBlock("") }
            assertTrue(empty.message!!.contains("blank")) { empty.message!! }

            assertThrows(IllegalArgumentException::class.java) { rawBlock("   ") }
            assertThrows(IllegalArgumentException::class.java) { rawBlock("\n") }
            assertThrows(IllegalArgumentException::class.java) { rawBlock(" \t\r\n ") }
        }

        /**
         * The one that matters. Spec 1.2: a terminator ends the block, so `M105\nM112` is a
         * temperature report *and* an emergency stop - and the transport frames what it is handed
         * without parsing it, leaving the `N` word on one side of the break and the checksum on the
         * other.
         */
        @Test
        fun `a line terminator inside the line would split the block`() {
            assertThrows(IllegalArgumentException::class.java) { rawBlock("M105\nM112") }
            assertThrows(IllegalArgumentException::class.java) { rawBlock("M105\r\nM112") }
            assertThrows(IllegalArgumentException::class.java) { rawBlock("M105\rM112") }
        }

        /** Spec 3.4a and 6: `;` runs to the end of the line, so it swallows the checksum behind it. */
        @Test
        fun `a semicolon would comment out the checksum`() {
            assertThrows(IllegalArgumentException::class.java) { rawBlock("M105 ; read it") }
            assertThrows(IllegalArgumentException::class.java) { rawBlock("; just a note") }
        }

        /** Spec 8.3: `*` is the checksum delimiter, so the firmware reads the rest as a checksum. */
        @Test
        fun `a star would be read as the checksum delimiter`() {
            assertThrows(IllegalArgumentException::class.java) { rawBlock("M105*42") }
        }

        /**
         * Spec 1.1: the portable wire is `0x20`-`0x7E`. That covers the accented letter a browser
         * will happily post, the control characters below space, and each half of a surrogate pair.
         */
        @Test
        fun `anything outside printable ASCII goes too`() {
            assertThrows(IllegalArgumentException::class.java) { rawBlock("M117 Café") }
            assertThrows(IllegalArgumentException::class.java) { rawBlock("M117 a\tb") }
            assertThrows(IllegalArgumentException::class.java) { rawBlock("M117 a" + 0.toChar() + "b") }
            assertThrows(IllegalArgumentException::class.java) { rawBlock("M117 a" + 127.toChar()) }
            assertThrows(IllegalArgumentException::class.java) { rawBlock("M117 😀") }
        }

        /**
         * The refusal has to be actionable: the operator typed the line, so the message names the
         * character and says where in the line it is.
         */
        @Test
        fun `the message names the character and its position`() {
            val semicolon = assertThrows(IllegalArgumentException::class.java) { rawBlock("M105 ; read it") }
            assertTrue(semicolon.message!!.contains("';'")) { semicolon.message!! }
            assertTrue(semicolon.message!!.contains("position 5")) { semicolon.message!! }

            val star = assertThrows(IllegalArgumentException::class.java) { rawBlock("M105*42") }
            assertTrue(star.message!!.contains("'*'")) { star.message!! }
            assertTrue(star.message!!.contains("position 4")) { star.message!! }
        }

        /**
         * A character that cannot be printed is named by its code point rather than pasted into the
         * message, where a raw newline would break the message across two lines of the log and an
         * invisible one would say nothing at all.
         */
        @Test
        fun `an unprintable character is named by its code point`() {
            val newline = assertThrows(IllegalArgumentException::class.java) { rawBlock("M105\nM112") }
            assertTrue(newline.message!!.contains("U+000A")) { newline.message!! }
            assertTrue(newline.message!!.contains("position 4")) { newline.message!! }
            assertTrue(!newline.message!!.contains("\n")) { newline.message!! }

            val cr = assertThrows(IllegalArgumentException::class.java) { rawBlock("M105\rM112") }
            assertTrue(cr.message!!.contains("U+000D")) { cr.message!! }

            val accent = assertThrows(IllegalArgumentException::class.java) { rawBlock("M117 Café") }
            assertTrue(accent.message!!.contains("U+00E9")) { accent.message!! }
            assertTrue(accent.message!!.contains("position 8")) { accent.message!! }
        }

        /** Positions are reported in the trimmed line, because the trimmed line is what is sent. */
        @Test
        fun `the position is the one in the trimmed line`() {
            val e = assertThrows(IllegalArgumentException::class.java) { rawBlock("    M105*42") }
            assertTrue(e.message!!.contains("position 4")) { e.message!! }
        }
    }

    @Nested
    inner class Length {

        private fun line(bytes: Int) = "M117 " + "A".repeat(bytes - 5)

        /** Both sides of spec 1.3's interoperable payload, exactly. */
        @Test
        fun `seventy six bytes is accepted and seventy seven is not`() {
            val fits = line(GLineLimit.INTEROPERABLE_PAYLOAD)
            assertEquals(76, GLineLimit.wireLength(fits))
            assertEquals(fits, rawBlock(fits))

            val over = line(GLineLimit.INTEROPERABLE_PAYLOAD + 1)
            assertEquals(77, GLineLimit.wireLength(over))
            assertThrows(IllegalArgumentException::class.java) { rawBlock(over) }
        }

        /**
         * The overrun is reported as a number of bytes, because that is what tells the operator how
         * much has to come out; `false` does not.
         */
        @Test
        fun `the message reports the overrun in bytes`() {
            val one = assertThrows(IllegalArgumentException::class.java) {
                rawBlock(line(GLineLimit.INTEROPERABLE_PAYLOAD + 1))
            }
            assertTrue(one.message!!.contains("1 byte")) { one.message!! }
            assertTrue(one.message!!.contains("76")) { one.message!! }

            val four = assertThrows(IllegalArgumentException::class.java) {
                rawBlock(line(GLineLimit.INTEROPERABLE_PAYLOAD + 4))
            }
            assertTrue(four.message!!.contains("4 bytes")) { four.message!! }
        }

        /** The limit is measured on what is sent, so surrounding whitespace does not spend it. */
        @Test
        fun `whitespace around the line does not count against the limit`() {
            val fits = line(GLineLimit.INTEROPERABLE_PAYLOAD)

            assertEquals(fits, rawBlock("   $fits   \n"))
        }

        /**
         * The limit is a byte count, not a character count - [GLineLimit.wireLength] is what says
         * so, and it is the function called here.
         *
         * **A line that is over the limit only once counted as bytes is unreachable through
         * [rawBlock], and deliberately so**: a multi-byte character is outside `0x20`-`0x7E`, so the
         * character rule refuses it first and every line that survives to the length rule is pure
         * one-byte ASCII, where bytes and characters are equal. Rather than write a length test that
         * would pass for the wrong reason, this states where the distinction actually lands - the
         * accented line is refused by spec 1.1, naming the character, and never reaches the budget.
         * `wireLength` is still the right function to measure with: it is the one spec 1.3's limit is
         * defined in terms of, and the character rule is what makes the two agree here.
         */
        @Test
        fun `a multi byte character is refused as a character before it is ever measured`() {
            val accented = "M117 " + "é".repeat(40)
            assertEquals(85, GLineLimit.wireLength(accented))
            assertEquals(45, accented.length)

            val e = assertThrows(IllegalArgumentException::class.java) { rawBlock(accented) }
            assertTrue(e.message!!.contains("U+00E9")) { e.message!! }
            assertTrue(!e.message!!.contains("too long")) { e.message!! }
        }

        /**
         * Why 76 and not `GLineLimit.payloadBudget(n)`: the line number is not known when a person
         * types the line. The transport assigns it at write time - `PublisherQueue` reorders by
         * priority, so a number assigned here would reach the wire out of sequence - and spec 1.3
         * names 76 for exactly that case. Checked as arithmetic: the longest line this accepts still
         * fits Marlin's buffer under any line number a send window will reach.
         */
        @Test
        fun `the accepted maximum still fits once the transport frames it`() {
            val fits = rawBlock(line(GLineLimit.INTEROPERABLE_PAYLOAD))

            assertTrue(GLineLimit.fits(GEncoder.frame(1, fits))) { GEncoder.frame(1, fits) }
            assertTrue(GLineLimit.fits(GEncoder.frame(999999, fits))) { GEncoder.frame(999999, fits) }
            assertInstanceOf(
                GFramedBlock::class.java,
                GBlockReader.blocks(GEncoder.frame(999999, fits)).first(),
            )
        }
    }

    @Nested
    inner class WhatThisDeliberatelyDoesNotGate {

        /**
         * Host transport policy is a layer above: an `N` word, `M110`, `M112`, `M28` and `M29` are
         * about the send window and the command queue a *particular* sender keeps, and `:gcode` must
         * not know what sender its caller has. They are gated in `:backend:core`; here they are
         * ordinary lines.
         */
        @Test
        fun `transport policy is not this function's rule`() {
            assertEquals("N7 M105", rawBlock("N7 M105"))
            assertEquals("M110 N0", rawBlock("M110 N0"))
            assertEquals("M112", rawBlock("M112"))
            assertEquals("M28 a.gco", rawBlock("M28 a.gco"))
            assertEquals("M29", rawBlock("M29"))
        }
    }
}
