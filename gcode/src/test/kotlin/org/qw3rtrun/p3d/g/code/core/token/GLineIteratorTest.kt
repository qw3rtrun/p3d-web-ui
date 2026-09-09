package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GLineIteratorTest {

    private val tokenizer = GTokenizer()

    @Test
    fun `empty tokens iterator has no elements and throws on next`() {
        val iter = GLineIterator(emptyList<GToken>().iterator())

        assertFalse(iter.hasNext())
        assertFalse(iter.hasNext())
        assertThrows<NoSuchElementException> {
            iter.next()
        }
    }

    @Test
    fun `single simple line without trailing line break`() {
        val input = "G1 X10 Y20"
        val iter = GLineIterator(tokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GSimpleLine::class.java, line)
        assertEquals(
            listOf(
                GLetter('G'),
                GInt(1),
                GSpace,
                GLetter('X'),
                GInt(10),
                GSpace,
                GLetter('Y'),
                GInt(20)
            ),
            line.raw()
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `single simple line with line break`() {
        val input = "G1 X10\n"
        val iter = GLineIterator(tokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GSimpleLine::class.java, line)
        assertEquals(
            listOf(
                GLetter('G'),
                GInt(1),
                GSpace,
                GLetter('X'),
                GInt(10),
                GLineBreak("\n")
            ),
            line.raw()
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `multiple simple lines`() {
        val input = "G28\nM104 S200\nG1 Z5\n"
        val lines = GLineIterator(tokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(3, lines.size)
        assertTrue(lines.all { it is GSimpleLine })

        assertEquals(listOf(GLetter('G'), GInt(28), GLineBreak("\n")), lines[0].raw())
        assertEquals(listOf(GLetter('M'), GInt(104), GSpace, GLetter('S'), GInt(200), GLineBreak("\n")), lines[1].raw())
        assertEquals(listOf(GLetter('G'), GInt(1), GSpace, GLetter('Z'), GInt(5), GLineBreak("\n")), lines[2].raw())
    }

    @Test
    fun `empty lines with line breaks`() {
        val input = "\n\n"
        val lines = GLineIterator(tokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(2, lines.size)
        assertTrue(lines.all { it is GMeaninglessLine }) { "expected two empty lines, got $lines" }
        assertEquals(listOf(GLineBreak("\n")), lines[0].raw())
        assertEquals(listOf(GLineBreak("\n")), lines[1].raw())
    }

    @Test
    fun `packet line with valid line number, checksum and trailing line break`() {
        val input = "N100 G1 X10 *45\n"
        val iter = GLineIterator(tokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GPacketLine::class.java, line)
        val packet = line as GPacketLine

        assertEquals(GInt(100), packet.number)
        assertEquals(GParameterWord(GChecksum, GInt(45)), packet.checksum)
        assertEquals(
            listOf(
                GMeaningless(GSpace),
                GParameterWord(GLetter('G'), GInt(1)),
                GMeaningless(GSpace),
                GParameterWord(GLetter('X'), GInt(10)),
                GMeaningless(GSpace)
            ),
            packet.payload
        )
        assertEquals(
            listOf(
                GParameterWord(GLetter('N'), GInt(100)),
                GMeaningless(GSpace),
                GParameterWord(GLetter('G'), GInt(1)),
                GMeaningless(GSpace),
                GParameterWord(GLetter('X'), GInt(10)),
                GMeaningless(GSpace),
                GParameterWord(GChecksum, GInt(45)),
                GMeaningless(GLineBreak("\n"))
            ),
            packet.whole
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `packet line without trailing line break`() {
        val input = "N100 G1 X10 *45"
        val iter = GLineIterator(tokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GPacketLine::class.java, line)
        val packet = line as GPacketLine

        assertEquals(GInt(100), packet.number)
        assertEquals(GParameterWord(GChecksum, GInt(45)), packet.checksum)
        assertEquals(
            listOf(
                GMeaningless(GSpace),
                GParameterWord(GLetter('G'), GInt(1)),
                GMeaningless(GSpace),
                GParameterWord(GLetter('X'), GInt(10)),
                GMeaningless(GSpace)
            ),
            packet.payload
        )
        assertEquals(
            listOf(
                GParameterWord(GLetter('N'), GInt(100)),
                GMeaningless(GSpace),
                GParameterWord(GLetter('G'), GInt(1)),
                GMeaningless(GSpace),
                GParameterWord(GLetter('X'), GInt(10)),
                GMeaningless(GSpace),
                GParameterWord(GChecksum, GInt(45))
            ),
            packet.whole
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `an N that is not followed by a number is a malformed line number`() {
        val line = GLineIterator(tokenizer.parse("N G1 X10 *45\n").iterator()).next()

        assertInstanceOf(GMalformedLineNumber::class.java, line)
        assertEquals("'N' is not followed by a line number", (line as GError).msg)
    }

    @Test
    fun `a star that is followed by a non-integer is a malformed checksum`() {
        val line = GLineIterator(tokenizer.parse("N100 G1 X10 *10.5\n").iterator()).next()

        assertInstanceOf(GMalformedChecksum::class.java, line)
        assertEquals(GInt(100), (line as GMalformedChecksum).number)
        assertEquals("'*' is not followed by a checksum value on line 100", line.msg)
    }

    @Test
    fun `a star followed by letters is a malformed checksum, not a missing one`() {
        // spec 8.1 (garbled value), not spec 7.3 (no value at all): the marker is on the wire, so
        // the host's decision is a resend of this line (spec 8.5), not a framing complaint.
        val line = GLineIterator(tokenizer.parse("N100 G1 X10 *ABC\n").iterator()).next()

        assertInstanceOf(GMalformedChecksum::class.java, line)
        assertEquals(GInt(100), (line as GMalformedChecksum).number)
        assertEquals("'*' is not followed by a checksum value on line 100", line.msg)
    }

    @Test
    fun `a checksum immediately after N is a malformed line number`() {
        val line = GLineIterator(tokenizer.parse("N*45 G1 X10\n").iterator()).next()

        assertInstanceOf(GMalformedLineNumber::class.java, line)
    }

    @Test
    fun `packet line with tail comment`() {
        val input = "N1 G28 *12 ;homing\n"
        val iter = GLineIterator(tokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GPacketLine::class.java, line)
        val packet = line as GPacketLine

        assertEquals(GInt(1), packet.number)
        assertEquals(GParameterWord(GChecksum, GInt(12)), packet.checksum)
        assertEquals(listOf(GMeaningless(GSpace), GParameterWord(GLetter('G'), GInt(28)), GMeaningless(GSpace)), packet.payload)
        assertEquals(
            listOf(
                GParameterWord(GLetter('N'), GInt(1)),
                GMeaningless(GSpace),
                GParameterWord(GLetter('G'), GInt(28)),
                GMeaningless(GSpace),
                GParameterWord(GChecksum, GInt(12)),
                GMeaningless(GSpace),
                GMeaningless(GTailComment("homing")),
                GMeaningless(GLineBreak("\n"))
            ),
            packet.whole
        )
    }

    @Test
    fun `comment lines and inline comments are parsed as simple lines`() {
        val input = "; full line comment\nG1 (feedrate) F1500\n"
        val lines = GLineIterator(tokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(2, lines.size)
        assertInstanceOf(GMeaninglessLine::class.java, lines[0])
        assertEquals(listOf(GTailComment(" full line comment"), GLineBreak("\n")), lines[0].raw())

        assertInstanceOf(GSimpleLine::class.java, lines[1])
        assertEquals(
            listOf(
                GLetter('G'),
                GInt(1),
                GSpace,
                GInlineComment("feedrate"),
                GSpace,
                GLetter('F'),
                GInt(1500),
                GLineBreak("\n")
            ),
            lines[1].raw()
        )
    }

    @Test
    fun `mixed sequence of packet lines and simple lines`() {
        val input = "N1 M110 N1*125\n; comment\nG28\nN2 G1 X10 *33\n"
        val lines = GLineIterator(tokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(4, lines.size)
        assertInstanceOf(GPacketLine::class.java, lines[0])
        assertInstanceOf(GMeaninglessLine::class.java, lines[1])
        assertInstanceOf(GSimpleLine::class.java, lines[2])
        assertInstanceOf(GPacketLine::class.java, lines[3])

        val firstPacket = lines[0] as GPacketLine
        assertEquals(GInt(1), firstPacket.number)
        assertEquals(GParameterWord(GChecksum, GInt(125)), firstPacket.checksum)

        val secondPacket = lines[3] as GPacketLine
        assertEquals(GInt(2), secondPacket.number)
        assertEquals(GParameterWord(GChecksum, GInt(33)), secondPacket.checksum)
    }

    @Test
    fun `hasNext is idempotent and does not advance iterator`() {
        val input = "G1 X1\nG2 X2\n"
        val iter = GLineIterator(tokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())

        val line1 = iter.next()
        assertInstanceOf(GSimpleLine::class.java, line1)
        assertEquals(listOf(GLetter('G'), GInt(1), GSpace, GLetter('X'), GInt(1), GLineBreak("\n")), line1.raw())

        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())

        val line2 = iter.next()
        assertInstanceOf(GSimpleLine::class.java, line2)
        assertEquals(listOf(GLetter('G'), GInt(2), GSpace, GLetter('X'), GInt(2), GLineBreak("\n")), line2.raw())

        assertFalse(iter.hasNext())
        assertFalse(iter.hasNext())
    }

    @Test
    fun `next after the last line throws`() {
        val iter = GLineIterator(tokenizer.parse("G1 X1\n").toList().iterator())

        iter.next()

        assertFalse(iter.hasNext())
        assertThrows<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `a line of nothing but whitespace is one empty line`() {
        val lines = GLineIterator(tokenizer.parse("   \n").iterator()).asSequence().toList()

        assertEquals(1, lines.size)
        assertInstanceOf(GMeaninglessLine::class.java, lines[0])
        assertEquals(listOf(GSpace, GSpace, GSpace, GLineBreak("\n")), lines[0].raw())
    }

    @Test
    fun `the payload of simple lines reproduces the input`() {
        val input = "G28\n; comment\nM104 S200\n\nG1 Z5"
        val lines = GLineIterator(tokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(5, lines.size)
        assertEquals(input, lines.joinToString("") { line -> line.raw().joinToString("") { it.rawText() } })
    }

    @Test
    fun `a line number without a checksum is a pairing error`() {
        // GCODE_spec.md section 7.3 requires both or neither.
        val line = GLineIterator(tokenizer.parse("N100 G1 X10\n").iterator()).next()

        assertInstanceOf(GMissingChecksum::class.java, line)
        assertEquals(GInt(100), (line as GMissingChecksum).number)
        assertEquals("line number 100 has no checksum", line.msg)
    }

    @Test
    fun `a checksum without a line number is a pairing error`() {
        val line = GLineIterator(tokenizer.parse("G1 X10*45\n").iterator()).next()

        assertInstanceOf(GMissingLineNumber::class.java, line)
        assertEquals("checksum without a line number", (line as GError).msg)
    }

    @Test
    fun `a star inside a tail comment does not make a packet`() {
        val line = GLineIterator(tokenizer.parse("N1 G28 ; 3*4\n").iterator()).next()

        assertFalse(line is GPacketLine)
        assertInstanceOf(GMissingChecksum::class.java, line)
    }

    @Test
    fun `a star inside a quoted string does not make a packet`() {
        val line = GLineIterator(tokenizer.parse("N1 M117 \"a*b\"\n").iterator()).next()

        assertFalse(line is GPacketLine)
        assertInstanceOf(GMissingChecksum::class.java, line)
    }

    @Test
    fun `packet line with zero line number and zero checksum`() {
        val packet = GLineIterator(tokenizer.parse("N0 G28*0\n").iterator()).next() as GPacketLine

        assertEquals(GInt(0), packet.number)
        assertEquals(GParameterWord(GChecksum, GInt(0)), packet.checksum)
        assertEquals(listOf(GMeaningless(GSpace), GParameterWord(GLetter('G'), GInt(28))), packet.payload)
    }

    @Test
    fun `packet line with a large line number and the maximum checksum`() {
        val packet = GLineIterator(tokenizer.parse("N999999 G28*255\n").iterator()).next() as GPacketLine

        assertEquals(GInt(999999), packet.number)
        assertEquals(GParameterWord(GChecksum, GInt(255)), packet.checksum)
    }

    @Test
    fun `consecutive packet lines`() {
        val lines = GLineIterator(tokenizer.parse("N1 G28*18\nN2 G28*17\nN3 T0*57\n").iterator())
            .asSequence().toList()

        assertEquals(3, lines.size)
        assertTrue(lines.all { it is GPacketLine })
        assertEquals(
            listOf(GInt(1), GInt(2), GInt(3)),
            lines.map { (it as GPacketLine).number }
        )
        assertEquals(
            listOf(GInt(18), GInt(17), GInt(57)),
            lines.map { (it as GPacketLine).checksum.value }
        )
    }

    @Test
    fun `a negative line number is distinguishable from a missing one`() {
        // Was a characterisation point: both used to report GInt(-1). The sentinel is gone, so a
        // real N-1 is a packet carrying -1 and a missing number is a different type entirely.
        // Spec 7.1 - Marlin tolerates a sign after N, so N-1 is not itself an error.
        val parsed = GLineIterator(tokenizer.parse("N-1 G28*12\n").iterator()).next()
        val mailformed = GLineIterator(tokenizer.parse("N G28*12\n").iterator()).next()

        assertInstanceOf(GPacketLine::class.java, parsed)
        assertEquals(GInt(-1, "-1"), (parsed as GPacketLine).number)
        assertInstanceOf(GMalformedLineNumber::class.java, mailformed)
    }

    @Test
    fun `line count matches the number of terminated lines`() {
        assertEquals(0, lineCount(""))
        assertEquals(1, lineCount("G28"))
        assertEquals(1, lineCount("G28\n"))
        assertEquals(2, lineCount("G28\nG28"))
        assertEquals(2, lineCount("G28\nG28\n"))
        assertEquals(3, lineCount("\n\n\n"))
    }

    private fun lineCount(gcode: String) =
        GLineIterator(tokenizer.parse(gcode).iterator()).asSequence().count()

    private fun lines(gcode: String): List<GLine> =
        GLineIterator(tokenizer.parse(gcode).iterator()).asSequence().toList()

    private fun line(gcode: String): GLine = lines(gcode).single()

    /**
     * The buffer boundaries a framer has to survive, per the `low-level-protocol-dev` framing rule.
     * `tail` used to be computed as `subList(i + 1, size - 1)`, which assumed a trailing line break:
     * every case here either threw or silently dropped a token (GCODE_TODO.md 1.3).
     */
    @Nested
    inner class BufferBoundaries {

        @ParameterizedTest
        @ValueSource(
            strings = [
                "", "\n", "\r\n", " ", " \n",
                "*", "*\n", "N", "N\n", "N*", "N*\n", "N1*", "N1*\n", "N1 G1*", "N1 G1*\n",
                "N1", "N1 ", "N1 G28*12", "N1 G28*12 ;c", "N1 G28*12 ;c\n",
                "G1 X1", "G1 X1\n", ";c", ";c\n", "(c", "\"a"
            ]
        )
        fun `no input makes the liner throw`(gcode: String) {
            // assertDoesNotThrow, not a value assertion: the point is that every boundary is
            // classified rather than crashing. What each one classifies as is asserted elsewhere.
            assertDoesNotThrow { lines(gcode) }
        }

        @Test
        fun `a checksum marker as the last token does not throw`() {
            // The marker is present with nothing after it: spec 8.1 malformed, not spec 7.3
            // missing. A truncated line and an unpaired line are different faults for a host.
            assertInstanceOf(GMalformedChecksum::class.java, line("N1*"))
            assertInstanceOf(GMalformedChecksum::class.java, line("N1 G1*"))
            assertInstanceOf(GMalformedLineNumber::class.java, line("N*"))
        }

        @Test
        fun `a packet without a terminator keeps its whole raw tokens`() {
            val packet = line("N1 G28*12 ;c") as GPacketLine

            assertEquals(
                listOf(
                    GParameterWord(GLetter('N'), GInt(1)),
                    GMeaningless(GSpace),
                    GParameterWord(GLetter('G'), GInt(28)),
                    GParameterWord(GChecksum, GInt(12)),
                    GMeaningless(GSpace),
                    GMeaningless(GTailComment("c"))
                ),
                packet.whole
            )
        }

        @Test
        fun `a packet with a terminator keeps its raw tokens including terminator`() {
            val packet = line("N1 G28*12 ;c\n") as GPacketLine

            assertEquals(
                listOf(
                    GParameterWord(GLetter('N'), GInt(1)),
                    GMeaningless(GSpace),
                    GParameterWord(GLetter('G'), GInt(28)),
                    GParameterWord(GChecksum, GInt(12)),
                    GMeaningless(GSpace),
                    GMeaningless(GTailComment("c")),
                    GMeaningless(GLineBreak("\n"))
                ),
                packet.whole
            )
        }

        @Test
        fun `a CRLF terminator is in raw tokens too`() {
            val packet = line("N1 G28*12\r\n") as GPacketLine

            assertEquals(
                listOf(
                    GParameterWord(GLetter('N'), GInt(1)),
                    GMeaningless(GSpace),
                    GParameterWord(GLetter('G'), GInt(28)),
                    GParameterWord(GChecksum, GInt(12)),
                    GMeaningless(GLineBreak("\r\n"))
                ),
                packet.whole
            )
        }

        @Test
        fun `a checksum marker in first position is a checksum without a line number`() {
            // spec 7.3: `*` without `N` is a pairing error wherever the marker sits - first
            // position included. The marker search used to skip index 0.
            assertInstanceOf(GMissingLineNumber::class.java, line("*12"))
            assertInstanceOf(GMissingLineNumber::class.java, line("*12\n"))
        }

        @Test
        fun `a lone checksum marker with no value is a checksum without a line number`() {
            // The pairing rule (spec 7.3) is tested before the field syntax (spec 8.1), so a `*`
            // with nothing after it reports the missing line number: a host cannot act on the
            // checksum value of a line that is not framed in the first place.
            assertInstanceOf(GMissingLineNumber::class.java, line("*"))
            assertInstanceOf(GMissingLineNumber::class.java, line("*\n"))
        }

        @Test
        fun `a lone N is a malformed line number, not a crash`() {
            assertInstanceOf(GMalformedLineNumber::class.java, line("N"))
        }
    }

    /** Spec section 5: a line of only whitespace and/or comments is a no-op. */
    @Nested
    inner class EmptyLines {

        @ParameterizedTest
        @ValueSource(strings = ["\n", "\r\n", "   \n", "\t\n", "; comment\n", "(comment)\n", "  ; c  \n"])
        fun `a line with no command element is empty`(gcode: String) {
            assertInstanceOf(GMeaninglessLine::class.java, line(gcode))
        }

        @Test
        fun `empty input produces no lines at all`() {
            assertEquals(emptyList<GLine>(), lines(""))
        }

        @Test
        fun `an empty line keeps its tokens so the input is not lost`() {
            assertEquals(listOf(GTailComment(" c"), GLineBreak("\n")), line("; c\n").raw())
        }

        @Test
        fun `a line with a command is not empty`() {
            assertInstanceOf(GSimpleLine::class.java, line("G28\n"))
            assertInstanceOf(GSimpleLine::class.java, line("  G28 ; c\n"))
        }

        @Test
        fun `an unknown token still counts as a meaningless line`() {
            assertInstanceOf(GMeaninglessLine::class.java, line("?\n"))
        }
    }

    /**
     * Spec section 2.1 - leading whitespace is a separator; section 2.2 - the dialects are
     * case-insensitive. Packet detection used to require `tokens[0] == GLetter('N')` exactly
     * (GCODE_TODO.md 1.8).
     */
    @Nested
    inner class PacketDetection {

        @ParameterizedTest
        @ValueSource(strings = ["N1 G28*18", "n1 G28*18"])
        fun `a packet is recognised whatever the case`(gcode: String) {
            val packet = line(gcode) as GPacketLine

            assertEquals(GInt(1), packet.number)
            assertEquals(GInt(18), packet.checksum.value)
        }

        @ParameterizedTest
        @ValueSource(strings = [" N1 G28*18", "\tN1 G28*18", "   n1 G28*18", "\t N1 G28*18"])
        fun `leading whitespace before N does not stop the line being a packet`(gcode: String) {
            // spec 2.1: space and tab are separators only, so the `N` is still the first *field*.
            val packet = line(gcode) as GPacketLine

            assertEquals(GInt(1), packet.number)
            assertEquals(GInt(18), packet.checksum.value)
        }

        @ParameterizedTest
        @ValueSource(strings = ["(c)N1 G28*18", "(c) N1 G28*18"])
        fun `a leading comment before N does not stop the line being a packet`(gcode: String) {
            // spec 6: a comment is not a field either, so it cannot displace the line number.
            val packet = line(gcode) as GPacketLine

            assertEquals(GInt(1), packet.number)
            assertEquals(GInt(18), packet.checksum.value)
        }

        @Test
        fun `the line number must be the first element`() {
            // spec 5: `N` is the first field. A trailing N is a parameter, not a line number.
            assertInstanceOf(GMissingLineNumber::class.java, line("G1 N5*10\n"))
        }

        @Test
        fun `an N parameter later in the line is not the line number`() {
            val packet = line("N1 M110 N7*125\n") as GPacketLine

            assertEquals(GInt(1), packet.number)
            assertEquals(
                listOf(
                    GMeaningless(GSpace),
                    GParameterWord(GLetter('M'), GInt(110)),
                    GMeaningless(GSpace),
                    GParameterWord(GLetter('N'), GInt(7))
                ),
                packet.payload
            )
        }

        @Test
        fun `whitespace between N and its number is allowed`() {
            // spec 2.1: whitespace is a separator, words assemble across it.
            val packet = line("N 1 G28*18\n") as GPacketLine

            assertEquals(GInt(1), packet.number)
        }
    }

    /**
     * Spec section 8.1 - the checksum field is `*` followed by an integer - and section 5, which
     * puts that field last before any comment.
     */
    @Nested
    inner class ChecksumField {

        @Test
        fun `whitespace between the marker and its value is allowed`() {
            // spec 2.1: whitespace is a separator, so the field assembles across it exactly as
            // `N 1` does. It still changes the bytes the checksum covers (spec 8.3).
            val packet = line("N1 G28 * 12\n") as GPacketLine

            assertEquals(GInt(12), packet.checksum.value)
        }

        @Test
        fun `a spaced marker with no line number is still a pairing error`() {
            assertInstanceOf(GMissingLineNumber::class.java, line("* 12"))
        }

        @Test
        fun `with two markers the last one is the checksum field`() {
            // spec 5 orders the checksum last, so the last `*` is the field and the earlier one
            // stays in the payload - where it is also part of the bytes the checksum covers
            // (spec 8.3). Marlin agrees: `get_serial_commands` uses `strrchr(command, '*')` and
            // XORs everything before it.
            val packet = line("N1 G28*12*13\n") as GPacketLine

            assertEquals(GInt(1), packet.number)
            assertEquals(GInt(13), packet.checksum.value)
            assertEquals(
                listOf(
                    GMeaningless(GSpace),
                    GParameterWord(GLetter('G'), GInt(28)),
                    GParameterWord(GChecksum, GInt(12))
                ),
                packet.payload
            )
        }
    }

    /**
     * Every token the tokenizer produced must appear in exactly one line, so the liner is a pure
     * regrouping of the stream. `GLine.raw()` is the single way to ask a line for its bytes back,
     * and it must hold for every line type - a `GPacketLine` decomposes its line, so it is the type
     * that can silently lose the `N` field, the `*` field and the terminator (TODO 1.20).
     */
    @Nested
    inner class NothingIsLost {

        private fun reassemble(line: GLine): String =
            line.raw().joinToString("") { it.rawText() }

        @Test
        fun `a packet line reproduces its own bytes through raw`() {
            val gcode = "N1 G28*18\n"

            val packet = lines(gcode).single() as GPacketLine

            assertEquals(gcode, packet.raw().joinToString("") { it.rawText() })
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "G28\n; comment\nM104 S200\n\nG1 Z5",
                "N1 G28*18\nN2 G1 X10*33\n",
                "N1 G28*12 ;homing\n",
                "  \n\t\nG1 X1\n",
                "G1 X1\r\nG1 X2\r\n",
                "N100 M110\nN101 M110 N100\n",
                // a packet whose line number is not element 0: `payload` starts after the `N`
                // field, so whatever precedes it is carried by `whole` alone and must still print
                " N1 G28*18\n\t(c) N2 G1 X10*33\n"
            ]
        )
        fun `the lines together reproduce the input, terminators aside`(gcode: String) {
            val actual = lines(gcode).joinToString("") { reassemble(it) }

            assertEquals(gcode, actual)
        }
    }
}
