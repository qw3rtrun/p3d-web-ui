package org.qw3rtrun.p3d.g.code.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.qw3rtrun.p3d.g.code.core.token.GInt

/**
 * Tests for the XOR checksum of GCODE_spec.md section 8.2 - the algorithm, its 8-bit masking and the
 * streaming contract of [CheckSumCalculator].
 */
class XorCheckSumTest {

    private fun checkSumOf(text: String): GInt = XorCheckSum().also { calc -> text.forEach(calc::add) }.get()

    @Test
    fun `checksum of nothing is zero`() {
        assertEquals(GInt(0), XorCheckSum().get())
        assertEquals(GInt(0), checkSumOf(""))
    }

    @Test
    fun `checksum of the spec example`() {
        // GCODE_spec.md section 8.3: N3 T0*57
        assertEquals(GInt(57), checkSumOf("N3 T0"))
    }

    @Test
    fun `spec example accumulates byte by byte`() {
        // The intermediate values of the worked example table in GCODE_spec.md section 8.3.
        val calc = XorCheckSum()

        calc.add('N')
        assertEquals(GInt(78), calc.get())
        calc.add('3')
        assertEquals(GInt(125), calc.get())
        calc.add(' ')
        assertEquals(GInt(93), calc.get())
        calc.add('T')
        assertEquals(GInt(9), calc.get())
        calc.add('0')
        assertEquals(GInt(57), calc.get())
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "N3 T0|57",
            "N1 G28|18",
            "N1 M110 N1|125",
            "N42 G1 X10.5 Y-3 E0.42 F1800|9",
            "N0 M110|35",
            "A|65",
            // Ported from the retired Java suite (todo 08): its three vectors were disjoint from
            // the set above, so deleting it would have lost them. `N2 M117 Hello World!` is the
            // valuable one - a payload carrying spaces and punctuation, which is exactly the byte
            // range section 8.3 is precise about.
            "N1 M115|39",
            "N1 M155 S1|97",
            "N2 M117 Hello World!|7"
        ],
        delimiter = '|'
    )
    fun `checksum of known lines`(payload: String, expected: Int) {
        assertEquals(GInt(expected), checkSumOf(payload))
    }

    @Test
    fun `the line number prefix is part of the checksum`() {
        // Section 8.3: the N field and its digits are covered, so the same command under a different
        // line number has a different checksum.
        val first = checkSumOf("N1 G28")
        val second = checkSumOf("N2 G28")

        assertEquals(GInt(18), first)
        assertEquals(GInt(17), second)
    }

    @Test
    fun `whitespace is part of the checksum`() {
        // Section 8.3: the bytes actually transmitted are checksummed, spaces included.
        assertEquals(GInt(97), checkSumOf("N1 G1 X0"))
        assertEquals(GInt(65), checkSumOf("N1 G1X0"))
    }

    @Test
    fun `adding the same character twice cancels out`() {
        val calc = XorCheckSum()

        calc.add('G')
        calc.add('G')

        assertEquals(GInt(0), calc.get())
    }

    @Test
    fun `the result does not depend on the order of the characters`() {
        assertEquals(checkSumOf("AB"), checkSumOf("BA"))
        assertEquals(checkSumOf("N1 G28"), checkSumOf("82G 1N"))
    }

    @Test
    fun `the result is masked to eight bits`() {
        // 'Я' is U+042F, so the raw XOR exceeds 0xff and has to be masked down.
        assertEquals(GInt(0x2F), checkSumOf("Я"))
        assertEquals(GInt(0), checkSumOf("Ā"))
        assertEquals(GInt(0xFF), checkSumOf("ÿ"))
    }

    @Test
    fun `get does not reset the accumulator`() {
        val calc = XorCheckSum()

        calc.add('N')
        calc.add('3')

        assertEquals(GInt(125), calc.get())
        assertEquals(GInt(125), calc.get())

        calc.add(' ')

        assertEquals(GInt(93), calc.get())
    }

    @Test
    fun `instances are independent`() {
        val first = XorCheckSum()
        val second = XorCheckSum()

        first.add('A')

        assertEquals(GInt(65), first.get())
        assertEquals(GInt(0), second.get())
    }

    @Test
    fun `it is a checksum calculator`() {
        val calc: CheckSumCalculator = XorCheckSum()

        calc.add('A')

        assertEquals(GInt(65), calc.get())
    }
}
