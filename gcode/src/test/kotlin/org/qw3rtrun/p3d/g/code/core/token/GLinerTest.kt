package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class GLinerTest {


    @Test
    fun `empty tokens iterator has no elements and throws on next`() {
        val iter = GLiner(emptyList<GToken>().iterator())

        assertFalse(iter.hasNext())
        assertFalse(iter.hasNext())
        assertThrows<NoSuchElementException> {
            iter.next()
        }
    }

    @Test
    fun `single simple line without trailing line break`() {
        val input = "G1 X10 Y20"
        val iter = GLiner(GTokenizer.parse(input).iterator())

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
            line.raw
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `single simple line with line break`() {
        val input = "G1 X10\n"
        val iter = GLiner(GTokenizer.parse(input).iterator())

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
            line.raw
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `multiple simple lines`() {
        val input = "G28\nM104 S200\nG1 Z5\n"
        val lines = GLiner(GTokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(3, lines.size)
        assertTrue(lines.all { it is GSimpleLine })

        assertEquals(listOf(GLetter('G'), GInt(28), GLineBreak("\n")), lines[0].raw)
        assertEquals(listOf(GLetter('M'), GInt(104), GSpace, GLetter('S'), GInt(200), GLineBreak("\n")), lines[1].raw)
        assertEquals(listOf(GLetter('G'), GInt(1), GSpace, GLetter('Z'), GInt(5), GLineBreak("\n")), lines[2].raw)
    }

    @Test
    fun `empty lines with line breaks`() {
        val input = "\n\n"
        val lines = GLiner(GTokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(2, lines.size)
        assertTrue(lines.all { it is GMeaninglessLine }) { "expected two empty lines, got $lines" }
        assertEquals(listOf(GLineBreak("\n")), lines[0].raw)
        assertEquals(listOf(GLineBreak("\n")), lines[1].raw)
    }

    @Test
    fun `packet line with valid line number, checksum and trailing line break`() {
        val input = "N100 G1 X10 *112\n"
        val iter = GLiner(GTokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GPacketLine::class.java, line)
        val packet = line as GPacketLine

        assertEquals(GInt(100), packet.number)
        assertEquals(GInt(112), packet.checksum)
        assertEquals(
            listOf(
                GSpace,
                GLetter('G'), GInt(1),
                GSpace,
                GLetter('X'), GInt(10),
                GSpace
            ),
            packet.body
        )
        assertEquals(
            listOf(
                GLetter('N'), GInt(100),
                GSpace,
                GLetter('G'), GInt(1),
                GSpace,
                GLetter('X'), GInt(10),
                GSpace,
                GChecksum, GInt(112),
                GLineBreak("\n")
            ),
            packet.raw
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `packet line without trailing line break`() {
        val input = "N100 G1 X10 *112"
        val iter = GLiner(GTokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GPacketLine::class.java, line)
        val packet = line as GPacketLine

        assertEquals(GInt(100), packet.number)
        assertEquals(GInt(112), packet.checksum)
        assertEquals(
            listOf(
                GSpace,
                GLetter('G'), GInt(1),
                GSpace,
                GLetter('X'), GInt(10),
                GSpace
            ),
            packet.body
        )
        assertEquals(
            listOf(
                GLetter('N'), GInt(100),
                GSpace,
                GLetter('G'), GInt(1),
                GSpace,
                GLetter('X'), GInt(10),
                GSpace,
                GChecksum, GInt(112)
            ),
            packet.raw
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `an N that is not followed by a number is a malformed line number`() {
        val line = GLiner(GTokenizer.parse("N G1 X10 *45\n").iterator()).next()

        assertInstanceOf(GMalformedLineNumber::class.java, line)
        assertEquals("'N' is not followed by a line number", (line as GError).msg)
    }

    @Test
    fun `a star that is followed by a non-integer is a malformed checksum`() {
        val line = GLiner(GTokenizer.parse("N100 G1 X10 *10.5\n").iterator()).next()

        assertInstanceOf(GMalformedChecksum::class.java, line)
        assertEquals(GInt(100), (line as GMalformedChecksum).number)
        assertEquals("'*' is not followed by a checksum value on line 100", line.msg)
    }

    @Test
    fun `a star followed by letters is a malformed checksum, not a missing one`() {
        // spec 8.1 (garbled value), not spec 7.3 (no value at all): the marker is on the wire, so
        // the host's decision is a resend of this line (spec 8.5), not a framing complaint.
        val line = GLiner(GTokenizer.parse("N100 G1 X10 *ABC\n").iterator()).next()

        assertInstanceOf(GMalformedChecksum::class.java, line)
        assertEquals(GInt(100), (line as GMalformedChecksum).number)
        assertEquals("'*' is not followed by a checksum value on line 100", line.msg)
    }

    @Test
    fun `a checksum immediately after N is a malformed line number`() {
        val line = GLiner(GTokenizer.parse("N*45 G1 X10\n").iterator()).next()

        assertInstanceOf(GMalformedLineNumber::class.java, line)
    }

    @Test
    fun `packet line with tail comment`() {
        val input = "N1 G28 *50 ;homing\n"
        val iter = GLiner(GTokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GPacketLine::class.java, line)
        val packet = line as GPacketLine

        assertEquals(GInt(1), packet.number)
        assertEquals(GInt(50), packet.checksum)
        assertEquals(listOf(GSpace, GLetter('G'), GInt(28), GSpace), packet.body)
        assertEquals(
            listOf(
                GLetter('N'), GInt(1),
                GSpace,
                GLetter('G'), GInt(28),
                GSpace,
                GChecksum, GInt(50),
                GSpace,
                GTailComment("homing"),
                GLineBreak("\n")
            ),
            packet.raw
        )
    }

    @Test
    fun `comment lines and inline comments are parsed as simple lines`() {
        val input = "; full line comment\nG1 (feedrate) F1500\n"
        val lines = GLiner(GTokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(2, lines.size)
        assertInstanceOf(GMeaninglessLine::class.java, lines[0])
        assertEquals(listOf(GTailComment(" full line comment"), GLineBreak("\n")), lines[0].raw)

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
            lines[1].raw
        )
    }

    @Test
    fun `mixed sequence of packet lines and simple lines`() {
        val input = "N1 M110 N1*125\n; comment\nG28\nN2 G1 X10 *115\n"
        val lines = GLiner(GTokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(4, lines.size)
        assertInstanceOf(GPacketLine::class.java, lines[0])
        assertInstanceOf(GMeaninglessLine::class.java, lines[1])
        assertInstanceOf(GSimpleLine::class.java, lines[2])
        assertInstanceOf(GPacketLine::class.java, lines[3])

        val firstPacket = lines[0] as GPacketLine
        assertEquals(GInt(1), firstPacket.number)
        assertEquals(GInt(125), firstPacket.checksum)

        val secondPacket = lines[3] as GPacketLine
        assertEquals(GInt(2), secondPacket.number)
        assertEquals(GInt(115), secondPacket.checksum)
    }

    @Test
    fun `hasNext is idempotent and does not advance iterator`() {
        val input = "G1 X1\nG2 X2\n"
        val iter = GLiner(GTokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())

        val line1 = iter.next()
        assertInstanceOf(GSimpleLine::class.java, line1)
        assertEquals(listOf(GLetter('G'), GInt(1), GSpace, GLetter('X'), GInt(1), GLineBreak("\n")), line1.raw)

        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())

        val line2 = iter.next()
        assertInstanceOf(GSimpleLine::class.java, line2)
        assertEquals(listOf(GLetter('G'), GInt(2), GSpace, GLetter('X'), GInt(2), GLineBreak("\n")), line2.raw)

        assertFalse(iter.hasNext())
        assertFalse(iter.hasNext())
    }

    @Test
    fun `next after the last line throws`() {
        val iter = GLiner(GTokenizer.parse("G1 X1\n").toList().iterator())

        iter.next()

        assertFalse(iter.hasNext())
        assertThrows<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `a line of nothing but whitespace is one empty line`() {
        val lines = GLiner(GTokenizer.parse("   \n").iterator()).asSequence().toList()

        assertEquals(1, lines.size)
        assertInstanceOf(GMeaninglessLine::class.java, lines[0])
        assertEquals(listOf(GSpace, GSpace, GSpace, GLineBreak("\n")), lines[0].raw)
    }

    @Test
    fun `the payload of simple lines reproduces the input`() {
        val input = "G28\n; comment\nM104 S200\n\nG1 Z5"
        val lines = GLiner(GTokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(5, lines.size)
        assertEquals(input, lines.joinToString("") { line -> line.raw.joinToString("") { it.rawText() } })
    }

    @Test
    fun `a line number without a checksum is a pairing error`() {
        // GCODE_spec.md section 7.3 requires both or neither.
        val line = GLiner(GTokenizer.parse("N100 G1 X10\n").iterator()).next()

        assertInstanceOf(GMissingChecksum::class.java, line)
        assertEquals(GInt(100), (line as GMissingChecksum).number)
        assertEquals("line number 100 has no checksum", line.msg)
    }

    @Test
    fun `a checksum without a line number is a pairing error`() {
        val line = GLiner(GTokenizer.parse("G1 X10*45\n").iterator()).next()

        assertInstanceOf(GMissingLineNumber::class.java, line)
        assertEquals("checksum without a line number", (line as GError).msg)
    }

    @Test
    fun `a star inside a tail comment does not make a packet`() {
        val line = GLiner(GTokenizer.parse("N1 G28 ; 3*4\n").iterator()).next()

        assertFalse(line is GPacketLine)
        assertInstanceOf(GMissingChecksum::class.java, line)
    }

    @Test
    fun `a star inside a quoted string does not make a packet`() {
        val line = GLiner(GTokenizer.parse("N1 M117 \"a*b\"\n").iterator()).next()

        assertFalse(line is GPacketLine)
        assertInstanceOf(GMissingChecksum::class.java, line)
    }

    @Test
    fun `packet line with zero line number and zero checksum`() {
        // Both zeros are real rather than chosen for looks: `N0 G1 X5 F3000` genuinely XORs to 0
        // (spec 8.2), so this is the case where a falsy checksum has to survive verification rather
        // than be read as "absent". The body is whatever makes that true.
        val packet = GLiner(GTokenizer.parse("N0 G1 X5 F3000*0\n").iterator()).next() as GPacketLine

        assertEquals(GInt(0), packet.number)
        assertEquals(GInt(0), packet.checksum)
        assertEquals(
            listOf(
                GSpace,
                GLetter('G'), GInt(1),
                GSpace,
                GLetter('X'), GInt(5),
                GSpace,
                GLetter('F'), GInt(3000)
            ),
            packet.body
        )
    }

    @Test
    fun `packet line with the largest checksum an ASCII line can carry`() {
        // 127, not 255. Spec 8.2 gives the XOR result the range 0-255, but spec 1.1 keeps the wire
        // in 7-bit ASCII, so every covered byte is below 0x80 and so is their XOR. 255 is
        // unreachable for any line this protocol can legally carry, and the fixture that used to
        // claim it was asserting on a value no generator could produce.
        val packet = GLiner(GTokenizer.parse("N999999 G1 E13*127\n").iterator()).next() as GPacketLine

        assertEquals(GInt(999999), packet.number)
        assertEquals(GInt(127), packet.checksum)
    }

    @Test
    fun `consecutive packet lines`() {
        val lines = GLiner(GTokenizer.parse("N1 G28*18\nN2 G28*17\nN3 T0*57\n").iterator())
            .asSequence().toList()

        assertEquals(3, lines.size)
        assertTrue(lines.all { it is GPacketLine })
        assertEquals(
            listOf(GInt(1), GInt(2), GInt(3)),
            lines.map { (it as GPacketLine).number }
        )
        assertEquals(
            listOf(GInt(18), GInt(17), GInt(57)),
            lines.map { (it as GPacketLine).checksum }
        )
    }

    @Test
    fun `a negative line number is distinguishable from a missing one`() {
        // Was a characterisation point: both used to report GInt(-1). The sentinel is gone, so a
        // real N-1 is a packet carrying -1 and a missing number is a different type entirely.
        // Spec 7.1 - Marlin tolerates a sign after N, so N-1 is not itself an error.
        val parsed = GLiner(GTokenizer.parse("N-1 G28*63\n").iterator()).next()
        val mailformed = GLiner(GTokenizer.parse("N G28*12\n").iterator()).next()

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
        GLiner(GTokenizer.parse(gcode).iterator()).asSequence().count()

    private fun lines(gcode: String): List<GLine> =
        GLiner(GTokenizer.parse(gcode).iterator()).asSequence().toList()

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
            val packet = line("N1 G28*18 ;c") as GPacketLine

            assertEquals(
                listOf(
                    GLetter('N'), GInt(1),
                    GSpace,
                    GLetter('G'), GInt(28),
                    GChecksum, GInt(18),
                    GSpace,
                    GTailComment("c")
                ),
                packet.raw
            )
        }

        @Test
        fun `a packet with a terminator keeps its raw tokens including terminator`() {
            val packet = line("N1 G28*18 ;c\n") as GPacketLine

            assertEquals(
                listOf(
                    GLetter('N'), GInt(1),
                    GSpace,
                    GLetter('G'), GInt(28),
                    GChecksum, GInt(18),
                    GSpace,
                    GTailComment("c"),
                    GLineBreak("\n")
                ),
                packet.raw
            )
        }

        @Test
        fun `a CRLF terminator is in raw tokens too`() {
            val packet = line("N1 G28*18\r\n") as GPacketLine

            assertEquals(
                listOf(
                    GLetter('N'), GInt(1),
                    GSpace,
                    GLetter('G'), GInt(28),
                    GChecksum, GInt(18),
                    GLineBreak("\r\n")
                ),
                packet.raw
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
            assertEquals(listOf(GTailComment(" c"), GLineBreak("\n")), line("; c\n").raw)
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
        @CsvSource(value = ["N1 G28*18|18", "n1 G28*50|50"], delimiter = '|')
        fun `a packet is recognised whatever the case`(gcode: String, checksum: Int) {
            // The two carry different checksums on purpose. Case-insensitivity is a *parsing* rule
            // (spec 2.2); the checksum is computed over the bytes as sent (spec 8.3), and 'n' and
            // 'N' differ by 0x20, so the same command under a lowercase marker is a different
            // 8-bit XOR. A single expected value here would only be provable by not checking one.
            val packet = line(gcode) as GPacketLine

            assertEquals(GInt(1), packet.number)
            assertEquals(GInt(checksum), packet.checksum)
        }

        @ParameterizedTest
        @CsvSource(
            value = [" N1 G28*18|18", "\tN1 G28*18|18", "   n1 G28*50|50", "\t N1 G28*18|18"],
            delimiter = '|',
            // Without this the CSV reader strips the very indentation these cases exist to carry,
            // silently turning all four into the unindented line and proving nothing.
            ignoreLeadingAndTrailingWhitespace = false
        )
        fun `leading whitespace before N does not stop the line being a packet`(gcode: String, checksum: Int) {
            // spec 2.1: space and tab are separators only, so the `N` is still the first *field*.
            //
            // The checksums say something sharper, and it is the reason these three uppercase cases
            // all carry 18 - the same value as the unindented `N1 G28*18`. Spec 8.3 starts the
            // covered range at the `N`, so **indentation is not checksummed**: one space, a tab or
            // a tab and a space all leave the answer at 18. Only the lowercase case moves, and it
            // moves because of the `n`, not because of the three spaces in front of it.
            val packet = line(gcode) as GPacketLine

            assertEquals(GInt(1), packet.number)
            assertEquals(GInt(checksum), packet.checksum)
        }

        @ParameterizedTest
        @ValueSource(strings = ["(c)N1 G28*18", "(c) N1 G28*18"])
        fun `a leading comment before N does not stop the line being a packet`(gcode: String) {
            // spec 6: a comment is not a field either, so it cannot displace the line number.
            val packet = line(gcode) as GPacketLine

            assertEquals(GInt(1), packet.number)
            assertEquals(GInt(18), packet.checksum)
        }

        @Test
        fun `the line number must be the first element`() {
            // spec 5: `N` is the first field. A trailing N is a parameter, not a line number.
            assertInstanceOf(GMissingLineNumber::class.java, line("G1 N5*10\n"))
        }

        @Test
        fun `an N parameter later in the line is not the line number`() {
            val packet = line("N1 M110 N7*123\n") as GPacketLine

            assertEquals(GInt(1), packet.number)
            assertEquals(
                listOf(
                    GSpace,
                    GLetter('M'), GInt(110),
                    GSpace,
                    GLetter('N'), GInt(7)
                ),
                packet.body
            )
        }

        @Test
        fun `whitespace between N and its number is allowed`() {
            // spec 2.1: whitespace is a separator, words assemble across it.
            //
            // 50, where `N1 G28` is 18: a space *inside* the line-number field is covered by spec
            // 8.3, while a space *before* the `N` is not. That is the whole of the difference
            // between this case and the indented ones above, and it is why the covered range has
            // to start at the `N` rather than at the first byte or the first field.
            val packet = line("N 1 G28*50\n") as GPacketLine

            assertEquals(GInt(1), packet.number)
            assertEquals(GInt(50), packet.checksum)
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
            val packet = line("N1 G28 * 50\n") as GPacketLine

            // 50, not `N1 G28`'s 18: the space before the marker falls inside the covered range
            // and the space after it does not, exactly as spec 8.3 describes.
            assertEquals(GInt(50), packet.checksum)
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
            val packet = line("N1 G28*12*59\n") as GPacketLine

            assertEquals(GInt(1), packet.number)
            // 59 is the XOR of `N1 G28*12` - the earlier marker and its digits are ordinary covered
            // bytes, since the range ends at the *last* `*`.
            assertEquals(GInt(59), packet.checksum)
            assertEquals(
                listOf(
                    GSpace,
                    GLetter('G'), GInt(28),
                    GChecksum, GInt(12)
                ),
                packet.body
            )
        }
    }

    /**
     * Spec section 8: the checksum is not merely parsed, it is **checked**, and a [GPacketLine] is
     * only ever built for a line that passed. These are the tests for that invariant - that the
     * right bytes go into the calculator, that the right algorithm is chosen, and that a line which
     * fails comes back as something a host can act on rather than as a packet.
     */
    @Nested
    inner class ChecksumVerification {

        @Test
        fun `a packet line is a line whose checksum was verified`() {
            // The invariant the type carries: there is no verify() to forget, because a GPacketLine
            // cannot be constructed for a line that did not pass.
            assertInstanceOf(GPacketLine::class.java, line("N1 G28*18"))
        }

        @Test
        fun `a well formed checksum that does not match the bytes is a failure, not a packet`() {
            val failed = line("N1 G28*12") as GCheckSumFailedLine

            assertEquals(GInt(1), failed.number)
            assertEquals(18, failed.expected.int)
            assertEquals(12, failed.received.int)
            assertEquals("checksum mismatch on line 1: computed 18, received 12", failed.msg)
        }

        @Test
        fun `a failed line is ordered but not checksum controlled`() {
            // spec 8.5 addresses a resend by line number, so the number has to be reachable. The
            // line is still not trustworthy, so it deliberately does not answer GCheckSumControlled
            // - a consumer matching on that interface is asking for lines it can act on.
            val failed = line("N1 G28*12")

            assertInstanceOf(GOrdered::class.java, failed)
            assertInstanceOf(GError::class.java, failed)
            assertFalse(failed is GCheckSumControlled)
        }

        @Test
        fun `a failed line still reproduces its own bytes`() {
            val gcode = "N1 G28*12 ;homing\n"

            val failed = lines(gcode).single()

            assertInstanceOf(GCheckSumFailedLine::class.java, failed)
            assertEquals(gcode, failed.raw.joinToString("") { it.rawText() })
        }

        @Test
        fun `a single corrupted byte before the star is detected`() {
            // `N1 G27*18` is `N1 G28*18` with one byte changed: the checksum no longer describes it.
            val failed = line("N1 G27*18") as GCheckSumFailedLine

            assertEquals(29, failed.expected.int)
            assertEquals(18, failed.received.int)
        }

        @Test
        fun `the xor checksum does not detect a transposition, and the crc does`() {
            // Spec 8.2 says so outright, and it is the reason 8.4 calls CRC16 strictly stronger.
            // Asserted rather than left implied: `N1 G82` is `N1 G28` with two bytes swapped, so
            // the commutative XOR cannot tell them apart and passes a line it should reject.
            assertInstanceOf(GPacketLine::class.java, line("N1 G28*18"))
            assertInstanceOf(GPacketLine::class.java, line("N1 G82*18"))

            // The same swap under the 5-digit field: 14291 belongs to `N1 G28` alone.
            assertInstanceOf(GPacketLine::class.java, line("N1 G28*14291"))
            assertInstanceOf(GCheckSumFailedLine::class.java, line("N1 G82*14291"))
        }

        @Test
        fun `five digits select the crc and it verifies`() {
            // Spec 8.4's worked vector, through the parser rather than the calculator.
            val packet = line("N3 T0*06939") as GPacketLine

            assertEquals(GInt(3), packet.number)
            assertEquals(6939, packet.checksum.int)
        }

        @Test
        fun `a zero padded crc is read as five digits and not as the number it parses to`() {
            // The trap the selector exists to avoid. `06939` has an `int` of 6939, which is four
            // digits; dispatching on the value would pick the XOR checksum and reject the line.
            // A CRC is zero-padded by definition (spec 8.4), so this is the common case, not a
            // corner one.
            val packet = line("N3 T0*06939") as GPacketLine

            assertEquals("06939", packet.checksum.lexeme)
            assertEquals(6939, packet.checksum.int)
        }

        @Test
        fun `one to three digits select the xor checksum`() {
            // The same line under both algorithms, to pin that the width alone decides.
            assertInstanceOf(GPacketLine::class.java, line("N1 G28*18"))
            assertInstanceOf(GPacketLine::class.java, line("N1 G28*14291"))
            assertInstanceOf(GCheckSumFailedLine::class.java, line("N1 G28*57"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["N1 G28*0018", "N1 G28*000018", "N1 G28*1234", "N1 G28*123456"])
        fun `a width no algorithm claims is a malformed field, not a mismatch`(gcode: String) {
            // spec 8.1 gives the digit count the job of choosing the algorithm, so 4 and 6+ digits
            // are not checksum fields at all. Nothing has been computed at the point this is
            // decided, which is exactly why it is not a mismatch - there is nothing to mismatch.
            // RepRapFirmware agrees: its validation switch rejects any other width outright.
            assertInstanceOf(GMalformedChecksum::class.java, line(gcode))
        }

        @Test
        fun `a signed value is not an unsigned int and so is not a checksum field`() {
            // spec 8.1 spells the field `*<unsigned-int>`. `+18` would otherwise be three
            // characters carrying 18 and would verify, which no firmware would accept.
            assertInstanceOf(GMalformedChecksum::class.java, line("N1 G28*+18"))
            assertInstanceOf(GMalformedChecksum::class.java, line("N1 G28*-18"))
        }

        @Test
        fun `a three digit value above the xor range is a mismatch`() {
            // spec 8.2 puts the XOR result in 0-255, so *300 cannot be any line's checksum. Both
            // Marlin and RepRapFirmware compare the parsed number and report a checksum error, so
            // this is a corrupt line to resend (spec 8.5) rather than a syntax complaint.
            val failed = line("N1 G28*300") as GCheckSumFailedLine

            assertEquals(18, failed.expected.int)
            assertEquals(300, failed.received.int)
        }

        @Test
        fun `indentation is outside the covered range and the star field is its end`() {
            // spec 8.3, stated as one assertion: everything from the `N` to just before the last
            // `*` is covered and nothing else is. The four lines below are the same covered bytes
            // (`N1 G28`, XOR 18) wearing different context - leading space, leading tab, a leading
            // comment, and a trailing comment after the field.
            assertInstanceOf(GPacketLine::class.java, line(" N1 G28*18"))
            assertInstanceOf(GPacketLine::class.java, line("\tN1 G28*18"))
            assertInstanceOf(GPacketLine::class.java, line("(c)N1 G28*18"))
            assertInstanceOf(GPacketLine::class.java, line("N1 G28*18 ;homing"))
            assertInstanceOf(GPacketLine::class.java, line("N1 G28*18\r\n"))
        }

        @Test
        fun `a syntax error is decided before any algorithm runs`() {
            // spec 7.3 then 8.1 then 8.2: pairing, then field syntax, then the bytes. A garbled
            // field never reaches a calculator, so it stays the fault it is.
            assertInstanceOf(GMalformedChecksum::class.java, line("N1 G28*ABC"))
            assertInstanceOf(GMissingLineNumber::class.java, line("*ABC"))
            assertInstanceOf(GMissingChecksum::class.java, line("N1 G28"))
            assertInstanceOf(GMalformedLineNumber::class.java, line("N G28*18"))
        }
    }

    /**
     * Every token the tokenizer produced must appear in exactly one line, so the liner is a pure
     * regrouping of the stream. `GLine.raw` is the single way to ask a line for its bytes back,
     * and it must hold for every line type - a `GPacketLine` decomposes its line, so it is the type
     * that can silently lose the `N` field, the `*` field and the terminator (TODO 1.20).
     */
    @Nested
    inner class NothingIsLost {

        private fun reassemble(line: GLine): String =
            line.raw.joinToString("") { it.rawText() }

        @Test
        fun `a packet line reproduces its own bytes through raw`() {
            val gcode = "N1 G28*18\n"

            val packet = lines(gcode).single() as GPacketLine

            assertEquals(gcode, packet.raw.joinToString("") { it.rawText() })
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "G28\n; comment\nM104 S200\n\nG1 Z5",
                "N1 G28*18\nN2 G1 X10*83\n",
                "N1 G28*18 ;homing\n",
                "  \n\t\nG1 X1\n",
                "G1 X1\r\nG1 X2\r\n",
                "N100 M110\nN101 M110 N100\n",
                // a packet whose line number is not element 0: `payload` starts after the `N`
                // field, so whatever precedes it is carried by `whole` alone and must still print
                " N1 G28*18\n\t(c) N2 G1 X10*83\n"
            ]
        )
        fun `the lines together reproduce the input, terminators aside`(gcode: String) {
            val actual = lines(gcode).joinToString("") { reassemble(it) }

            assertEquals(gcode, actual)
        }
    }
}
