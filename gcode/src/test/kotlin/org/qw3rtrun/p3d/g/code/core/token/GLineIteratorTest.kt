package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
            line.payload
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
            line.payload
        )
        assertFalse(iter.hasNext())
    }

    @Test
    fun `multiple simple lines`() {
        val input = "G28\nM104 S200\nG1 Z5\n"
        val lines = GLineIterator(tokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(3, lines.size)
        assertTrue(lines.all { it is GSimpleLine })

        assertEquals(listOf(GLetter('G'), GInt(28), GLineBreak("\n")), lines[0].payload)
        assertEquals(listOf(GLetter('M'), GInt(104), GSpace, GLetter('S'), GInt(200), GLineBreak("\n")), lines[1].payload)
        assertEquals(listOf(GLetter('G'), GInt(1), GSpace, GLetter('Z'), GInt(5), GLineBreak("\n")), lines[2].payload)
    }

    @Test
    fun `empty lines with line breaks`() {
        val input = "\n\n"
        val lines = GLineIterator(tokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(2, lines.size)
        assertTrue(lines.all { it is GSimpleLine })
        assertEquals(listOf(GLineBreak("\n")), lines[0].payload)
        assertEquals(listOf(GLineBreak("\n")), lines[1].payload)
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
        assertEquals(GCheckSumValue(GChecksum, GInt(45)), packet.checksum)
        assertEquals(
            listOf(
                GSpace,
                GLetter('G'),
                GInt(1),
                GSpace,
                GLetter('X'),
                GInt(10),
                GSpace
            ),
            packet.payload
        )
        assertEquals(listOf(GInt(45)), packet.tail)
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
        assertEquals(GCheckSumValue(GChecksum, GInt(45)), packet.checksum)
        assertEquals(
            listOf(
                GSpace,
                GLetter('G'),
                GInt(1),
                GSpace,
                GLetter('X'),
                GInt(10),
                GSpace
            ),
            packet.payload
        )
        assertEquals(emptyList<GToken>(), packet.tail)
        assertFalse(iter.hasNext())
    }

    @Test
    fun `packet line without line number defaults to negative one`() {
        val input = "N G1 X10 *45\n"
        val iter = GLineIterator(tokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GPacketLine::class.java, line)
        val packet = line as GPacketLine

        assertEquals(GInt(-1), packet.number)
        assertEquals(GCheckSumValue(GChecksum, GInt(45)), packet.checksum)
    }

    @Test
    fun `packet line without valid checksum value defaults checksum value to negative one`() {
        val input = "N100 G1 X10 *ABC\n"
        val iter = GLineIterator(tokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GPacketLine::class.java, line)
        val packet = line as GPacketLine

        assertEquals(GInt(100), packet.number)
        assertEquals(GCheckSumValue(GChecksum, GInt(-1)), packet.checksum)
    }

    @Test
    fun `packet line with checksum at index 1`() {
        val input = "N*45 G1 X10\n"
        val iter = GLineIterator(tokenizer.parse(input).iterator())

        assertTrue(iter.hasNext())
        val line = iter.next()
        assertInstanceOf(GPacketLine::class.java, line)
        val packet = line as GPacketLine

        assertEquals(GInt(-1), packet.number)
        assertEquals(GCheckSumValue(GChecksum, GInt(-1)), packet.checksum)
        assertEquals(emptyList<GToken>(), packet.tail)
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
        assertEquals(GCheckSumValue(GChecksum, GInt(12)), packet.checksum)
        assertEquals(listOf(GSpace, GLetter('G'), GInt(28), GSpace), packet.payload)
        assertEquals(listOf(GInt(12), GSpace, GTailComment("homing")), packet.tail)
    }

    @Test
    fun `comment lines and inline comments are parsed as simple lines`() {
        val input = "; full line comment\nG1 (feedrate) F1500\n"
        val lines = GLineIterator(tokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(2, lines.size)
        assertInstanceOf(GSimpleLine::class.java, lines[0])
        assertEquals(listOf(GTailComment(" full line comment"), GLineBreak("\n")), lines[0].payload)

        assertInstanceOf(GSimpleLine::class.java, lines[1])
        assertEquals(
            listOf(
                GLetter('G'),
                GInt(1),
                GSpace,
                GInlineComment("feedrate)"),
                GSpace,
                GLetter('F'),
                GInt(1500),
                GLineBreak("\n")
            ),
            lines[1].payload
        )
    }

    @Test
    fun `mixed sequence of packet lines and simple lines`() {
        val input = "N1 M110 N1*125\n; comment\nG28\nN2 G1 X10 *33\n"
        val lines = GLineIterator(tokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(4, lines.size)
        assertInstanceOf(GPacketLine::class.java, lines[0])
        assertInstanceOf(GSimpleLine::class.java, lines[1])
        assertInstanceOf(GSimpleLine::class.java, lines[2])
        assertInstanceOf(GPacketLine::class.java, lines[3])

        val firstPacket = lines[0] as GPacketLine
        assertEquals(GInt(1), firstPacket.number)
        assertEquals(GCheckSumValue(GChecksum, GInt(125)), firstPacket.checksum)

        val secondPacket = lines[3] as GPacketLine
        assertEquals(GInt(2), secondPacket.number)
        assertEquals(GCheckSumValue(GChecksum, GInt(33)), secondPacket.checksum)
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
        assertEquals(listOf(GLetter('G'), GInt(1), GSpace, GLetter('X'), GInt(1), GLineBreak("\n")), line1.payload)

        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())

        val line2 = iter.next()
        assertInstanceOf(GSimpleLine::class.java, line2)
        assertEquals(listOf(GLetter('G'), GInt(2), GSpace, GLetter('X'), GInt(2), GLineBreak("\n")), line2.payload)

        assertFalse(iter.hasNext())
        assertFalse(iter.hasNext())
    }

    @Test
    fun `next after the last line throws`() {
        // Token list iterator on purpose: neither iterator guards on `hasNext()`, they propagate
        // what the underlying source throws (GCODE_TODO.md 1.17).
        val iter = GLineIterator(tokenizer.parse("G1 X1\n").toList().iterator())

        iter.next()

        assertFalse(iter.hasNext())
        assertThrows<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `a line of nothing but whitespace is one line`() {
        val lines = GLineIterator(tokenizer.parse("   \n").iterator()).asSequence().toList()

        assertEquals(1, lines.size)
        assertInstanceOf(GSimpleLine::class.java, lines[0])
        assertEquals(listOf(GSpace, GSpace, GSpace, GLineBreak("\n")), lines[0].payload)
    }

    @Test
    fun `the payload of simple lines reproduces the input`() {
        val input = "G28\n; comment\nM104 S200\n\nG1 Z5"
        val lines = GLineIterator(tokenizer.parse(input).iterator()).asSequence().toList()

        assertEquals(5, lines.size)
        assertEquals(input, lines.joinToString("") { line -> line.payload.joinToString("") { it.rawText() } })
    }

    @Test
    fun `a line number without a checksum is not a packet`() {
        // GCODE_spec.md section 7.3 requires both or neither; reporting it as an error is still
        // open (GCODE_TODO.md 4.3), so this only pins that it is not treated as a valid packet.
        val line = GLineIterator(tokenizer.parse("N100 G1 X10\n").iterator()).next()

        assertFalse(line is GPacketLine)
    }

    @Test
    fun `a checksum without a line number is not a packet`() {
        val line = GLineIterator(tokenizer.parse("G1 X10*45\n").iterator()).next()

        assertFalse(line is GPacketLine)
    }

    @Test
    fun `a star inside a tail comment does not make a packet`() {
        val line = GLineIterator(tokenizer.parse("N1 G28 ; 3*4\n").iterator()).next()

        assertFalse(line is GPacketLine)
    }

    @Test
    fun `a star inside a quoted string does not make a packet`() {
        val line = GLineIterator(tokenizer.parse("N1 M117 \"a*b\"\n").iterator()).next()

        assertFalse(line is GPacketLine)
    }

    @Test
    fun `packet line with zero line number and zero checksum`() {
        val packet = GLineIterator(tokenizer.parse("N0 G28*0\n").iterator()).next() as GPacketLine

        assertEquals(GInt(0), packet.number)
        assertEquals(GCheckSumValue(GChecksum, GInt(0)), packet.checksum)
        assertEquals(listOf(GSpace, GLetter('G'), GInt(28)), packet.payload)
    }

    @Test
    fun `packet line with a large line number and the maximum checksum`() {
        val packet = GLineIterator(tokenizer.parse("N999999 G28*255\n").iterator()).next() as GPacketLine

        assertEquals(GInt(999999), packet.number)
        assertEquals(GCheckSumValue(GChecksum, GInt(255)), packet.checksum)
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
    fun `a negative line number is indistinguishable from the missing-number sentinel`() {
        // Characterisation point, not an expectation: `GLiner` reports "no line number" as
        // GInt(-1), which a real `N-1` now also produces. GCODE_TODO.md 4.3 replaces the
        // sentinel with a nullable field or a GError subtype; this test must change with it.
        val parsed = GLineIterator(tokenizer.parse("N-1 G28*12\n").iterator()).next() as GPacketLine
        val missing = GLineIterator(tokenizer.parse("N G28*12\n").iterator()).next() as GPacketLine

        assertEquals(GInt(-1), parsed.number)
        assertEquals(GInt(-1), missing.number)
        assertEquals(parsed.number, missing.number)
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
}
