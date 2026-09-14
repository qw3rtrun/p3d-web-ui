package org.qw3rtrun.p3d.g.code.core

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for the algorithm selector of GCODE_spec.md section 8.1 - the rule that the **digit count**,
 * and nothing else, says which of the two algorithms a `*` field is written in.
 */
class CheckSumsTest {

    @ParameterizedTest
    @ValueSource(strings = ["0", "9", "18", "57", "255", "999", "000"])
    fun `one to three digits select the xor checksum`(lexeme: String) {
        assertInstanceOf(XorCheckSum::class.java, checkSumCalculatorFor(lexeme))
    }

    @ParameterizedTest
    @ValueSource(strings = ["06939", "14291", "00000", "65535", "00057"])
    fun `five digits select the crc`(lexeme: String) {
        assertInstanceOf(Crc16CheckSum::class.java, checkSumCalculatorFor(lexeme))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1234", "0018", "123456", "000000", "1234567"])
    fun `no other width is a checksum field`(lexeme: String) {
        // Section 8.1 leaves 4 and 6+ digits unclaimed, and RepRapFirmware's validation switch
        // rejects them outright rather than guessing at an algorithm. `null` is a syntax answer:
        // nothing has been computed, so there is nothing to call a mismatch.
        assertNull(checkSumCalculatorFor(lexeme))
    }

    @Test
    fun `an empty field is not a checksum field`() {
        assertNull(checkSumCalculatorFor(""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["+18", "-18", "1 8", "1a", "0x12", "18.", "١٢٣"])
    fun `only digits make a checksum field`(lexeme: String) {
        // Section 8.1 spells the field `*<unsigned-int>`. A sign is not part of one, and neither is
        // a non-ASCII digit - `١٢٣` is three characters long and would otherwise select the XOR
        // checksum, which is the same Unicode trap todo 01 took out of the lexer.
        assertNull(checkSumCalculatorFor(lexeme))
    }

    @Test
    fun `the width is read off the lexeme and never off the value`() {
        // The whole reason this function takes a String. `00057` and `57` carry the same number and
        // are different fields: five digits is a CRC, two is an XOR checksum. A CRC is zero-padded
        // by definition (section 8.4), so reading the width off the parsed value would misroute the
        // common case, not an exotic one.
        assertInstanceOf(Crc16CheckSum::class.java, checkSumCalculatorFor("00057"))
        assertInstanceOf(XorCheckSum::class.java, checkSumCalculatorFor("57"))
    }

    @Test
    fun `each call hands back a fresh calculator`() {
        // The caller feeds it a line; a shared instance would carry the previous line's state.
        val first = checkSumCalculatorFor("18")!!
        val second = checkSumCalculatorFor("18")!!

        first.add('A')

        assert(first.get().int != second.get().int)
    }
}
