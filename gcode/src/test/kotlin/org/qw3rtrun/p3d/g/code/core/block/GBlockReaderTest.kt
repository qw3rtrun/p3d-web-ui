package org.qw3rtrun.p3d.g.code.core.block

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.g.code.core.token.GChecksum
import org.qw3rtrun.p3d.g.code.core.token.GInlineComment
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GLetter
import org.qw3rtrun.p3d.g.code.core.token.GLineBreak
import org.qw3rtrun.p3d.g.code.core.token.GSpace
import org.qw3rtrun.p3d.g.code.core.token.GTailComment
import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.GTokenizer

class GBlockReaderTest {


    @Test
    fun `empty tokens iterator has no elements and throws on next`() {
        val iter = GBlockReader(emptyList<GToken>().iterator())

        assertFalse(iter.hasNext())
        assertFalse(iter.hasNext())
        assertThrows<NoSuchElementException> {
            iter.next()
        }
    }

    @Test
    fun `single simple block without trailing line break`() {
        val input = "G1 X10 Y20"
        val iter = GBlockReader(GTokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val block = iter.next()
        assertInstanceOf(GPlainBlock::class.java, block)
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
            block.raw
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `single simple block with line break`() {
        val input = "G1 X10\n"
        val iter = GBlockReader(GTokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val block = iter.next()
        assertInstanceOf(GPlainBlock::class.java, block)
        assertEquals(
            listOf(
                GLetter('G'),
                GInt(1),
                GSpace,
                GLetter('X'),
                GInt(10),
                GLineBreak("\n")
            ),
            block.raw
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `multiple simple blocks`() {
        val input = "G28\nM104 S200\nG1 Z5\n"
        val blocks = GBlockReader(GTokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it is GPlainBlock })

        assertEquals(listOf(GLetter('G'), GInt(28), GLineBreak("\n")), blocks[0].raw)
        assertEquals(listOf(GLetter('M'), GInt(104), GSpace, GLetter('S'), GInt(200), GLineBreak("\n")), blocks[1].raw)
        assertEquals(listOf(GLetter('G'), GInt(1), GSpace, GLetter('Z'), GInt(5), GLineBreak("\n")), blocks[2].raw)
    }

    @Test
    fun `empty blocks with line breaks`() {
        val input = "\n\n"
        val blocks = GBlockReader(GTokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is GMeaninglessBlock }) { "expected two empty blocks, got $blocks" }
        assertEquals(listOf(GLineBreak("\n")), blocks[0].raw)
        assertEquals(listOf(GLineBreak("\n")), blocks[1].raw)
    }

    @Test
    fun `framed block with valid line number, checksum and trailing line break`() {
        val input = "N100 G1 X10 *112\n"
        val iter = GBlockReader(GTokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val block = iter.next()
        assertInstanceOf(GFramedBlock::class.java, block)
        val framed = block as GFramedBlock

        assertEquals(GInt(100), framed.number)
        assertEquals(GInt(112), framed.checksum)
        assertEquals(
            listOf(
                GSpace,
                GLetter('G'), GInt(1),
                GSpace,
                GLetter('X'), GInt(10),
                GSpace
            ),
            framed.body
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
            framed.raw
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `framed block without trailing line break`() {
        val input = "N100 G1 X10 *112"
        val iter = GBlockReader(GTokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val block = iter.next()
        assertInstanceOf(GFramedBlock::class.java, block)
        val framed = block as GFramedBlock

        assertEquals(GInt(100), framed.number)
        assertEquals(GInt(112), framed.checksum)
        assertEquals(
            listOf(
                GSpace,
                GLetter('G'), GInt(1),
                GSpace,
                GLetter('X'), GInt(10),
                GSpace
            ),
            framed.body
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
            framed.raw
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `an N that is not followed by a number is a malformed line number`() {
        val block = GBlockReader(GTokenizer.parse("N G1 X10 *45\n").iterator()).next()

        assertInstanceOf(GMalformedLineNumber::class.java, block)
        assertEquals("'N' is not followed by a line number", (block as GError).msg)
    }

    @Test
    fun `a star that is followed by a non-integer is a malformed checksum`() {
        val block = GBlockReader(GTokenizer.parse("N100 G1 X10 *10.5\n").iterator()).next()

        assertInstanceOf(GMalformedChecksum::class.java, block)
        assertEquals(GInt(100), (block as GMalformedChecksum).number)
        assertEquals("'*' is not followed by a checksum value on line 100", block.msg)
    }

    @Test
    fun `a star followed by letters is a malformed checksum, not a missing one`() {
        // spec 8.1 (garbled value), not spec 7.3 (no value at all): the marker is on the wire, so
        // the host's decision is a resend of this block (spec 8.5), not a framing complaint.
        val block = GBlockReader(GTokenizer.parse("N100 G1 X10 *ABC\n").iterator()).next()

        assertInstanceOf(GMalformedChecksum::class.java, block)
        assertEquals(GInt(100), (block as GMalformedChecksum).number)
        assertEquals("'*' is not followed by a checksum value on line 100", block.msg)
    }

    @Test
    fun `a checksum immediately after N is a malformed line number`() {
        val block = GBlockReader(GTokenizer.parse("N*45 G1 X10\n").iterator()).next()

        assertInstanceOf(GMalformedLineNumber::class.java, block)
    }

    @Test
    fun `framed block with tail comment`() {
        val input = "N1 G28 *50 ;homing\n"
        val iter = GBlockReader(GTokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val block = iter.next()
        assertInstanceOf(GFramedBlock::class.java, block)
        val framed = block as GFramedBlock

        assertEquals(GInt(1), framed.number)
        assertEquals(GInt(50), framed.checksum)
        assertEquals(listOf(GSpace, GLetter('G'), GInt(28), GSpace), framed.body)
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
            framed.raw
        )
    }

    @Test
    fun `comment blocks and inline comments are parsed as simple blocks`() {
        val input = "; full block comment\nG1 (feedrate) F1500\n"
        val blocks = GBlockReader(GTokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(2, blocks.size)
        assertInstanceOf(GMeaninglessBlock::class.java, blocks[0])
        assertEquals(listOf(GTailComment(" full block comment"), GLineBreak("\n")), blocks[0].raw)

        assertInstanceOf(GPlainBlock::class.java, blocks[1])
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
            blocks[1].raw
        )
    }

    @Test
    fun `mixed sequence of framed blocks and simple blocks`() {
        val input = "N1 M110 N1*125\n; comment\nG28\nN2 G1 X10 *115\n"
        val blocks = GBlockReader(GTokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(4, blocks.size)
        assertInstanceOf(GFramedBlock::class.java, blocks[0])
        assertInstanceOf(GMeaninglessBlock::class.java, blocks[1])
        assertInstanceOf(GPlainBlock::class.java, blocks[2])
        assertInstanceOf(GFramedBlock::class.java, blocks[3])

        val firstFramed = blocks[0] as GFramedBlock
        assertEquals(GInt(1), firstFramed.number)
        assertEquals(GInt(125), firstFramed.checksum)

        val secondFramed = blocks[3] as GFramedBlock
        assertEquals(GInt(2), secondFramed.number)
        assertEquals(GInt(115), secondFramed.checksum)
    }

    @Test
    fun `hasNext is idempotent and does not advance iterator`() {
        val input = "G1 X1\nG2 X2\n"
        val iter = GBlockReader(GTokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())

        val block1 = iter.next()
        assertInstanceOf(GPlainBlock::class.java, block1)
        assertEquals(listOf(GLetter('G'), GInt(1), GSpace, GLetter('X'), GInt(1), GLineBreak("\n")), block1.raw)

        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())

        val block2 = iter.next()
        assertInstanceOf(GPlainBlock::class.java, block2)
        assertEquals(listOf(GLetter('G'), GInt(2), GSpace, GLetter('X'), GInt(2), GLineBreak("\n")), block2.raw)

        assertFalse(iter.hasNext())
        assertFalse(iter.hasNext())
    }

    @Test
    fun `next after the last block throws`() {
        val iter = GBlockReader(GTokenizer.parse("G1 X1\n").toList().iterator())

        iter.next()

        assertFalse(iter.hasNext())
        assertThrows<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `a block of nothing but whitespace is one empty block`() {
        val blocks = GBlockReader(GTokenizer.parse("   \n").iterator()).asSequence().toList()

        assertEquals(1, blocks.size)
        assertInstanceOf(GMeaninglessBlock::class.java, blocks[0])
        assertEquals(listOf(GSpace, GSpace, GSpace, GLineBreak("\n")), blocks[0].raw)
    }

    @Test
    fun `the payload of simple blocks reproduces the input`() {
        val input = "G28\n; comment\nM104 S200\n\nG1 Z5"
        val blocks = GBlockReader(GTokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(5, blocks.size)
        assertEquals(input, blocks.joinToString("") { block -> block.raw.joinToString("") { it.rawText() } })
    }

    @Test
    fun `a line number without a checksum is a pairing error`() {
        // GCODE_spec.md section 7.3 requires both or neither.
        val block = GBlockReader(GTokenizer.parse("N100 G1 X10\n").iterator()).next()

        assertInstanceOf(GMissingChecksum::class.java, block)
        assertEquals(GInt(100), (block as GMissingChecksum).number)
        assertEquals("line number 100 has no checksum", block.msg)
    }

    @Test
    fun `a checksum without a line number is a pairing error`() {
        val block = GBlockReader(GTokenizer.parse("G1 X10*45\n").iterator()).next()

        assertInstanceOf(GMissingLineNumber::class.java, block)
        assertEquals("checksum without a line number", (block as GError).msg)
    }

    @Test
    fun `a star inside a tail comment does not make a framed block`() {
        val block = GBlockReader(GTokenizer.parse("N1 G28 ; 3*4\n").iterator()).next()

        assertFalse(block is GFramedBlock)
        assertInstanceOf(GMissingChecksum::class.java, block)
    }

    @Test
    fun `a star inside a quoted string does not make a framed block`() {
        val block = GBlockReader(GTokenizer.parse("N1 M117 \"a*b\"\n").iterator()).next()

        assertFalse(block is GFramedBlock)
        assertInstanceOf(GMissingChecksum::class.java, block)
    }

    @Test
    fun `framed block with zero line number and zero checksum`() {
        // Both zeros are real rather than chosen for looks: `N0 G1 X5 F3000` genuinely XORs to 0
        // (spec 8.2), so this is the case where a falsy checksum has to survive verification rather
        // than be read as "absent". The body is whatever makes that true.
        val framed = GBlockReader(GTokenizer.parse("N0 G1 X5 F3000*0\n").iterator()).next() as GFramedBlock

        assertEquals(GInt(0), framed.number)
        assertEquals(GInt(0), framed.checksum)
        assertEquals(
            listOf(
                GSpace,
                GLetter('G'), GInt(1),
                GSpace,
                GLetter('X'), GInt(5),
                GSpace,
                GLetter('F'), GInt(3000)
            ),
            framed.body
        )
    }

    @Test
    fun `framed block with the largest checksum an ASCII block can carry`() {
        // 127, not 255. Spec 8.2 gives the XOR result the range 0-255, but spec 1.1 keeps the wire
        // in 7-bit ASCII, so every covered byte is below 0x80 and so is their XOR. 255 is
        // unreachable for any block this protocol can legally carry, and the fixture that used to
        // claim it was asserting on a value no generator could produce.
        val framed = GBlockReader(GTokenizer.parse("N999999 G1 E13*127\n").iterator()).next() as GFramedBlock

        assertEquals(GInt(999999), framed.number)
        assertEquals(GInt(127), framed.checksum)
    }

    @Test
    fun `consecutive framed blocks`() {
        val blocks = GBlockReader(GTokenizer.parse("N1 G28*18\nN2 G28*17\nN3 T0*57\n").iterator())
            .asSequence().toList()

        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it is GFramedBlock })
        assertEquals(
            listOf(GInt(1), GInt(2), GInt(3)),
            blocks.map { (it as GFramedBlock).number }
        )
        assertEquals(
            listOf(GInt(18), GInt(17), GInt(57)),
            blocks.map { (it as GFramedBlock).checksum }
        )
    }

    @Test
    fun `a negative line number is distinguishable from a missing one`() {
        // Was a characterisation point: both used to report GInt(-1). The sentinel is gone, so a
        // real N-1 is a framed block carrying -1 and a missing number is a different type entirely.
        // Spec 7.1 - Marlin tolerates a sign after N, so N-1 is not itself an error.
        val parsed = GBlockReader(GTokenizer.parse("N-1 G28*63\n").iterator()).next()
        val mailformed = GBlockReader(GTokenizer.parse("N G28*12\n").iterator()).next()

        assertInstanceOf(GFramedBlock::class.java, parsed)
        assertEquals(GInt(-1, "-1"), (parsed as GFramedBlock).number)
        assertInstanceOf(GMalformedLineNumber::class.java, mailformed)
    }

    @Test
    fun `block count matches the number of terminated blocks`() {
        assertEquals(0, lineCount(""))
        assertEquals(1, lineCount("G28"))
        assertEquals(1, lineCount("G28\n"))
        assertEquals(2, lineCount("G28\nG28"))
        assertEquals(2, lineCount("G28\nG28\n"))
        assertEquals(3, lineCount("\n\n\n"))
    }

    private fun lineCount(gcode: String) =
        GBlockReader(GTokenizer.parse(gcode).iterator()).asSequence().count()

    private fun blocks(gcode: String): List<GBlock> =
        GBlockReader(GTokenizer.parse(gcode).iterator()).asSequence().toList()

    private fun block(gcode: String): GBlock = blocks(gcode).single()

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
        fun `no input makes the reader throw`(gcode: String) {
            // assertDoesNotThrow, not a value assertion: the point is that every boundary is
            // classified rather than crashing. What each one classifies as is asserted elsewhere.
            assertDoesNotThrow { blocks(gcode) }
        }

        @Test
        fun `a checksum marker as the last token does not throw`() {
            // The marker is present with nothing after it: spec 8.1 malformed, not spec 7.3
            // missing. A truncated block and an unpaired block are different faults for a host.
            assertInstanceOf(GMalformedChecksum::class.java, block("N1*"))
            assertInstanceOf(GMalformedChecksum::class.java, block("N1 G1*"))
            assertInstanceOf(GMalformedLineNumber::class.java, block("N*"))
        }

        @Test
        fun `a framed block without a terminator keeps its whole raw tokens`() {
            val framed = block("N1 G28*18 ;c") as GFramedBlock

            assertEquals(
                listOf(
                    GLetter('N'), GInt(1),
                    GSpace,
                    GLetter('G'), GInt(28),
                    GChecksum, GInt(18),
                    GSpace,
                    GTailComment("c")
                ),
                framed.raw
            )
        }

        @Test
        fun `a framed block with a terminator keeps its raw tokens including terminator`() {
            val framed = block("N1 G28*18 ;c\n") as GFramedBlock

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
                framed.raw
            )
        }

        @Test
        fun `a CRLF terminator is in raw tokens too`() {
            val framed = block("N1 G28*18\r\n") as GFramedBlock

            assertEquals(
                listOf(
                    GLetter('N'), GInt(1),
                    GSpace,
                    GLetter('G'), GInt(28),
                    GChecksum, GInt(18),
                    GLineBreak("\r\n")
                ),
                framed.raw
            )
        }

        @Test
        fun `a checksum marker in first position is a checksum without a line number`() {
            // spec 7.3: `*` without `N` is a pairing error wherever the marker sits - first
            // position included. The marker search used to skip index 0.
            assertInstanceOf(GMissingLineNumber::class.java, block("*12"))
            assertInstanceOf(GMissingLineNumber::class.java, block("*12\n"))
        }

        @Test
        fun `a lone checksum marker with no value is a checksum without a line number`() {
            // The pairing rule (spec 7.3) is tested before the field syntax (spec 8.1), so a `*`
            // with nothing after it reports the missing line number: a host cannot act on the
            // checksum value of a block that is not framed in the first place.
            assertInstanceOf(GMissingLineNumber::class.java, block("*"))
            assertInstanceOf(GMissingLineNumber::class.java, block("*\n"))
        }

        @Test
        fun `a lone N is a malformed line number, not a crash`() {
            assertInstanceOf(GMalformedLineNumber::class.java, block("N"))
        }
    }

    /** Spec section 5: a block of only whitespace and/or comments is a no-op. */
    @Nested
    inner class EmptyBlocks {

        @ParameterizedTest
        @ValueSource(strings = ["\n", "\r\n", "   \n", "\t\n", "; comment\n", "(comment)\n", "  ; c  \n"])
        fun `a block with no command element is empty`(gcode: String) {
            assertInstanceOf(GMeaninglessBlock::class.java, block(gcode))
        }

        @Test
        fun `empty input produces no blocks at all`() {
            assertEquals(emptyList<GBlock>(), blocks(""))
        }

        @Test
        fun `an empty block keeps its tokens so the input is not lost`() {
            assertEquals(listOf(GTailComment(" c"), GLineBreak("\n")), block("; c\n").raw)
        }

        @Test
        fun `a block with a command is not empty`() {
            assertInstanceOf(GPlainBlock::class.java, block("G28\n"))
            assertInstanceOf(GPlainBlock::class.java, block("  G28 ; c\n"))
        }

        @Test
        fun `an unknown token still counts as a meaningless block`() {
            assertInstanceOf(GMeaninglessBlock::class.java, block("?\n"))
        }
    }

    /**
     * Spec section 2.1 - leading whitespace is a separator; section 2.2 - the dialects are
     * case-insensitive. Framing detection used to require `tokens[0] == GLetter('N')` exactly
     * (GCODE_TODO.md 1.8).
     */
    @Nested
    inner class FramingDetection {

        @ParameterizedTest
        @CsvSource(value = ["N1 G28*18|18", "n1 G28*50|50"], delimiter = '|')
        fun `a framed block is recognised whatever the case`(gcode: String, checksum: Int) {
            // The two carry different checksums on purpose. Case-insensitivity is a *parsing* rule
            // (spec 2.2); the checksum is computed over the bytes as sent (spec 8.3), and 'n' and
            // 'N' differ by 0x20, so the same command under a lowercase marker is a different
            // 8-bit XOR. A single expected value here would only be provable by not checking one.
            val framed = block(gcode) as GFramedBlock

            assertEquals(GInt(1), framed.number)
            assertEquals(GInt(checksum), framed.checksum)
        }

        @ParameterizedTest
        @CsvSource(
            value = [" N1 G28*18|18", "\tN1 G28*18|18", "   n1 G28*50|50", "\t N1 G28*18|18"],
            delimiter = '|',
            // Without this the CSV reader strips the very indentation these cases exist to carry,
            // silently turning all four into the unindented block and proving nothing.
            ignoreLeadingAndTrailingWhitespace = false
        )
        fun `leading whitespace before N does not stop the block being framed`(gcode: String, checksum: Int) {
            // spec 2.1: space and tab are separators only, so the `N` is still the first *field*.
            //
            // The checksums say something sharper, and it is the reason these three uppercase cases
            // all carry 18 - the same value as the unindented `N1 G28*18`. Spec 8.3 starts the
            // covered range at the `N`, so **indentation is not checksummed**: one space, a tab or
            // a tab and a space all leave the answer at 18. Only the lowercase case moves, and it
            // moves because of the `n`, not because of the three spaces in front of it.
            val framed = block(gcode) as GFramedBlock

            assertEquals(GInt(1), framed.number)
            assertEquals(GInt(checksum), framed.checksum)
        }

        @ParameterizedTest
        @ValueSource(strings = ["(c)N1 G28*18", "(c) N1 G28*18"])
        fun `a leading comment before N does not stop the block being framed`(gcode: String) {
            // spec 6: a comment is not a field either, so it cannot displace the line number.
            val framed = block(gcode) as GFramedBlock

            assertEquals(GInt(1), framed.number)
            assertEquals(GInt(18), framed.checksum)
        }

        @Test
        fun `the line number must be the first element`() {
            // spec 5: `N` is the first field. A trailing N is a parameter, not a line number.
            assertInstanceOf(GMissingLineNumber::class.java, block("G1 N5*10\n"))
        }

        @Test
        fun `an N parameter later in the block is not the line number`() {
            val framed = block("N1 M110 N7*123\n") as GFramedBlock

            assertEquals(GInt(1), framed.number)
            assertEquals(
                listOf(
                    GSpace,
                    GLetter('M'), GInt(110),
                    GSpace,
                    GLetter('N'), GInt(7)
                ),
                framed.body
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
            val framed = block("N 1 G28*50\n") as GFramedBlock

            assertEquals(GInt(1), framed.number)
            assertEquals(GInt(50), framed.checksum)
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
            val framed = block("N1 G28 * 50\n") as GFramedBlock

            // 50, not `N1 G28`'s 18: the space before the marker falls inside the covered range
            // and the space after it does not, exactly as spec 8.3 describes.
            assertEquals(GInt(50), framed.checksum)
        }

        @Test
        fun `a spaced marker with no line number is still a pairing error`() {
            assertInstanceOf(GMissingLineNumber::class.java, block("* 12"))
        }

        @Test
        fun `with two markers the last one is the checksum field`() {
            // spec 5 orders the checksum last, so the last `*` is the field and the earlier one
            // stays in the payload - where it is also part of the bytes the checksum covers
            // (spec 8.3). Marlin agrees: `get_serial_commands` uses `strrchr(command, '*')` and
            // XORs everything before it.
            val framed = block("N1 G28*12*59\n") as GFramedBlock

            assertEquals(GInt(1), framed.number)
            // 59 is the XOR of `N1 G28*12` - the earlier marker and its digits are ordinary covered
            // bytes, since the range ends at the *last* `*`.
            assertEquals(GInt(59), framed.checksum)
            assertEquals(
                listOf(
                    GSpace,
                    GLetter('G'), GInt(28),
                    GChecksum, GInt(12)
                ),
                framed.body
            )
        }
    }

    /**
     * Spec section 8: the checksum is not merely parsed, it is **checked**, and a [GFramedBlock] is
     * only ever built for a block that passed. These are the tests for that invariant - that the
     * right bytes go into the calculator, that the right algorithm is chosen, and that a block which
     * fails comes back as something a host can act on rather than as a framed.
     */
    @Nested
    inner class ChecksumVerification {

        @Test
        fun `a framed block is a block whose checksum was verified`() {
            // The invariant the type carries: there is no verify() to forget, because a GFramedBlock
            // cannot be constructed for a block that did not pass.
            assertInstanceOf(GFramedBlock::class.java, block("N1 G28*18"))
        }

        @Test
        fun `a well formed checksum that does not match the bytes is a failure, not a framed block`() {
            val failed = block("N1 G28*12") as GCheckSumFailedBlock

            assertEquals(GInt(1), failed.number)
            assertEquals(18, failed.expected.int)
            assertEquals(12, failed.received.int)
            assertEquals("checksum mismatch on line 1: computed 18, received 12", failed.msg)
        }

        @Test
        fun `a failed block is ordered but not checksum controlled`() {
            // spec 8.5 addresses a resend by line number, so the number has to be reachable. The
            // block is still not trustworthy, so it deliberately does not answer GCheckSumControlled
            // - a consumer matching on that interface is asking for blocks it can act on.
            val failed = block("N1 G28*12")

            assertInstanceOf(GOrdered::class.java, failed)
            assertInstanceOf(GError::class.java, failed)
            assertFalse(failed is GCheckSumControlled)
        }

        @Test
        fun `a failed block still reproduces its own bytes`() {
            val gcode = "N1 G28*12 ;homing\n"

            val failed = blocks(gcode).single()

            assertInstanceOf(GCheckSumFailedBlock::class.java, failed)
            assertEquals(gcode, failed.raw.joinToString("") { it.rawText() })
        }

        @Test
        fun `a single corrupted byte before the star is detected`() {
            // `N1 G27*18` is `N1 G28*18` with one byte changed: the checksum no longer describes it.
            val failed = block("N1 G27*18") as GCheckSumFailedBlock

            assertEquals(29, failed.expected.int)
            assertEquals(18, failed.received.int)
        }

        @Test
        fun `the xor checksum does not detect a transposition, and the crc does`() {
            // Spec 8.2 says so outright, and it is the reason 8.4 calls CRC16 strictly stronger.
            // Asserted rather than left implied: `N1 G82` is `N1 G28` with two bytes swapped, so
            // the commutative XOR cannot tell them apart and passes a block it should reject.
            assertInstanceOf(GFramedBlock::class.java, block("N1 G28*18"))
            assertInstanceOf(GFramedBlock::class.java, block("N1 G82*18"))

            // The same swap under the 5-digit field: 14291 belongs to `N1 G28` alone.
            assertInstanceOf(GFramedBlock::class.java, block("N1 G28*14291"))
            assertInstanceOf(GCheckSumFailedBlock::class.java, block("N1 G82*14291"))
        }

        @Test
        fun `five digits select the crc and it verifies`() {
            // Spec 8.4's worked vector, through the parser rather than the calculator.
            val framed = block("N3 T0*06939") as GFramedBlock

            assertEquals(GInt(3), framed.number)
            assertEquals(6939, framed.checksum.int)
        }

        @Test
        fun `a zero padded crc is read as five digits and not as the number it parses to`() {
            // The trap the selector exists to avoid. `06939` has an `int` of 6939, which is four
            // digits; dispatching on the value would pick the XOR checksum and reject the block.
            // A CRC is zero-padded by definition (spec 8.4), so this is the common case, not a
            // corner one.
            val framed = block("N3 T0*06939") as GFramedBlock

            assertEquals("06939", framed.checksum.lexeme)
            assertEquals(6939, framed.checksum.int)
        }

        @Test
        fun `one to three digits select the xor checksum`() {
            // The same block under both algorithms, to pin that the width alone decides.
            assertInstanceOf(GFramedBlock::class.java, block("N1 G28*18"))
            assertInstanceOf(GFramedBlock::class.java, block("N1 G28*14291"))
            assertInstanceOf(GCheckSumFailedBlock::class.java, block("N1 G28*57"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["N1 G28*0018", "N1 G28*000018", "N1 G28*1234", "N1 G28*123456"])
        fun `a width no algorithm claims is a malformed field, not a mismatch`(gcode: String) {
            // spec 8.1 gives the digit count the job of choosing the algorithm, so 4 and 6+ digits
            // are not checksum fields at all. Nothing has been computed at the point this is
            // decided, which is exactly why it is not a mismatch - there is nothing to mismatch.
            // RepRapFirmware agrees: its validation switch rejects any other width outright.
            assertInstanceOf(GMalformedChecksum::class.java, block(gcode))
        }

        @Test
        fun `a signed value is not an unsigned int and so is not a checksum field`() {
            // spec 8.1 spells the field `*<unsigned-int>`. `+18` would otherwise be three
            // characters carrying 18 and would verify, which no firmware would accept.
            assertInstanceOf(GMalformedChecksum::class.java, block("N1 G28*+18"))
            assertInstanceOf(GMalformedChecksum::class.java, block("N1 G28*-18"))
        }

        @Test
        fun `a three digit value above the xor range is a mismatch`() {
            // spec 8.2 puts the XOR result in 0-255, so *300 cannot be any block's checksum. Both
            // Marlin and RepRapFirmware compare the parsed number and report a checksum error, so
            // this is a corrupt block to resend (spec 8.5) rather than a syntax complaint.
            val failed = block("N1 G28*300") as GCheckSumFailedBlock

            assertEquals(18, failed.expected.int)
            assertEquals(300, failed.received.int)
        }

        @Test
        fun `indentation is outside the covered range and the star field is its end`() {
            // spec 8.3, stated as one assertion: everything from the `N` to just before the last
            // `*` is covered and nothing else is. The four blocks below are the same covered bytes
            // (`N1 G28`, XOR 18) wearing different context - leading space, leading tab, a leading
            // comment, and a trailing comment after the field.
            assertInstanceOf(GFramedBlock::class.java, block(" N1 G28*18"))
            assertInstanceOf(GFramedBlock::class.java, block("\tN1 G28*18"))
            assertInstanceOf(GFramedBlock::class.java, block("(c)N1 G28*18"))
            assertInstanceOf(GFramedBlock::class.java, block("N1 G28*18 ;homing"))
            assertInstanceOf(GFramedBlock::class.java, block("N1 G28*18\r\n"))
        }

        @Test
        fun `a syntax error is decided before any algorithm runs`() {
            // spec 7.3 then 8.1 then 8.2: pairing, then field syntax, then the bytes. A garbled
            // field never reaches a calculator, so it stays the fault it is.
            assertInstanceOf(GMalformedChecksum::class.java, block("N1 G28*ABC"))
            assertInstanceOf(GMissingLineNumber::class.java, block("*ABC"))
            assertInstanceOf(GMissingChecksum::class.java, block("N1 G28"))
            assertInstanceOf(GMalformedLineNumber::class.java, block("N G28*18"))
        }
    }

    /**
     * Every token the tokenizer produced must appear in exactly one block, so the reader is a pure
     * regrouping of the stream. `GBlock.raw` is the single way to ask a block for its bytes back,
     * and it must hold for every block type - a `GFramedBlock` decomposes its block, so it is the type
     * that can silently lose the `N` field, the `*` field and the terminator (TODO 1.20).
     */
    @Nested
    inner class NothingIsLost {

        private fun reassemble(block: GBlock): String =
            block.raw.joinToString("") { it.rawText() }

        @Test
        fun `a framed block reproduces its own bytes through raw`() {
            val gcode = "N1 G28*18\n"

            val framed = blocks(gcode).single() as GFramedBlock

            assertEquals(gcode, framed.raw.joinToString("") { it.rawText() })
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
                // a framed block whose line number is not element 0: `payload` starts after the `N`
                // field, so whatever precedes it is carried by `whole` alone and must still print
                " N1 G28*18\n\t(c) N2 G1 X10*83\n"
            ]
        )
        fun `the blocks together reproduce the input, terminators aside`(gcode: String) {
            val actual = blocks(gcode).joinToString("") { reassemble(it) }

            assertEquals(gcode, actual)
        }
    }

    @Nested
    inner class ReadPipeline {

        @Test
        fun `blocks reads a text into classified blocks`() {
            val classes = GBlockReader.blocks("G28\nN1 G28*18\n").map { it::class }.toList()

            assertEquals(listOf(GPlainBlock::class, GFramedBlock::class), classes)
        }

        @Test
        fun `blocks is re-iterable`() {
            val blocks = GBlockReader.blocks("G28\nN1 G28*18\n")

            assertEquals(blocks.toList(), blocks.toList())
        }

        @Test
        fun `the block count survives the round trip`() {
            val program = listOf("; header", "G28", "G1 X1 F100", "", "G90")
            val blocks = GBlockReader(GTokenizer.parseLines(program.asSequence()).iterator())

            assertEquals(program.size, blocks.asSequence().count())
        }
    }
}
