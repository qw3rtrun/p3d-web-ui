package org.qw3rtrun.p3d.g.code.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.qw3rtrun.p3d.g.code.core.token.GInt

/**
 * Tests for the CRC16 of GCODE_spec.md section 8.4.
 *
 * The variant is **CRC-16/XMODEM** - polynomial `0x1021`, initial value `0x0000`, MSB-first, no
 * final XOR - and that is not a guess: it is read off RepRapFirmware's own source, which is the only
 * firmware that accepts this field. See section 8.4 for the citation. Four variants all answer to
 * "CCITT CRC-16 with poly 0x1021" and they disagree on every vector below, so these numbers are the
 * test: if the variant is ever changed, every one of them moves.
 */
class Crc16CheckSumTest {

    private fun crcOf(text: String): GInt = Crc16CheckSum().also { calc -> text.forEach(calc::add) }.get()

    @Test
    fun `crc of nothing is zero`() {
        // Init value 0x0000, unmodified: the distinguishing mark against the 0xFFFF variants.
        assertEquals(0, Crc16CheckSum().get().int)
        assertEquals(0, crcOf("").int)
    }

    @Test
    fun `crc of the spec example`() {
        // GCODE_spec.md section 8.4: N3 T0*06939
        assertEquals(6939, crcOf("N3 T0").int)
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "N3 T0|6939",
            "N1 G28|14291",
            "N1 M115|30753",
            "N2 G28|55553",
            "A|22757"
        ],
        delimiter = '|'
    )
    fun `crc of known lines`(payload: String, expected: Int) {
        assertEquals(expected, crcOf(payload).int)
    }

    @Test
    fun `the variant is XMODEM and not one of its three lookalikes`() {
        // CCITT-FALSE would be 2583, KERMIT 20362, X-25 33021. Section 8.4 records where the answer
        // comes from; this test is what stops a well-meaning "fix" to a more famous variant.
        val crc = crcOf("N3 T0").int

        assertEquals(6939, crc)
        assertNotEquals(2583, crc)
        assertNotEquals(20362, crc)
        assertNotEquals(33021, crc)
    }

    @Test
    fun `the lexeme is zero padded to five digits`() {
        // Section 8.4's width rule is what selects this algorithm over the XOR checksum, so a CRC
        // that renders as four digits would not be recognised as a CRC at all.
        assertEquals("06939", crcOf("N3 T0").lexeme)
        assertEquals("00000", crcOf("").lexeme)
        assertEquals("14291", crcOf("N1 G28").lexeme)
    }

    @Test
    fun `the result is masked to sixteen bits`() {
        val crc = crcOf("N42 G1 X10.5 Y-3 E0.42 F1800").int

        assertEquals(51198, crc)
        assert(crc in 0..0xffff)
    }

    @Test
    fun `unlike the xor checksum the order of the characters matters`() {
        // The reason section 8.4 calls this strictly stronger: XOR is commutative, this is not.
        assertNotEquals(crcOf("AB").int, crcOf("BA").int)
        assertEquals(XorCheckSum().also { "AB".forEach(it::add) }.get().int,
                     XorCheckSum().also { "BA".forEach(it::add) }.get().int)
    }

    @Test
    fun `whitespace is part of the crc`() {
        // Section 8.3's byte range applies to both algorithms.
        assertEquals(42823, crcOf("N1 G1 X0").int)
        assertEquals(61853, crcOf("N1 G1X0").int)
    }

    @Test
    fun `get does not reset the accumulator`() {
        val calc = Crc16CheckSum()

        "N3 T0".forEach(calc::add)

        assertEquals(6939, calc.get().int)
        assertEquals(6939, calc.get().int)
    }

    @Test
    fun `instances are independent`() {
        val first = Crc16CheckSum()
        val second = Crc16CheckSum()

        first.add('A')

        assertEquals(22757, first.get().int)
        assertEquals(0, second.get().int)
    }

    @Test
    fun `it is a checksum calculator`() {
        val calc: CheckSumCalculator = Crc16CheckSum()

        calc.add('A')

        assertEquals(22757, calc.get().int)
    }
}
