package org.qw3rtrun.p3d.g.code.core

import org.qw3rtrun.p3d.g.code.core.token.GInt

/**
 * The CRC16 of GCODE_spec.md section 8.4, the second algorithm the `*` field can carry.
 *
 * **The variant is CRC-16/XMODEM**: polynomial `0x1021`, initial value `0x0000`, MSB-first (no
 * reflection of input or output), no final XOR. That is read off RepRapFirmware's `src/Storage/CRC16`
 * - the only firmware that accepts this field - and not chosen by plausibility; section 8.4 carries
 * the citation and the vector. Three other algorithms answer to "CCITT CRC-16 with poly 0x1021" and
 * none of them agrees with this one on any input, so guessing would have rejected every genuine line.
 *
 * Computed a bit at a time rather than through a 256-entry table. RRF uses a table because it is
 * flashing a PanelDue; here the input is one serial line, the two forms are identical in output, and
 * the bitwise form carries no static data for a port to transcribe.
 *
 * Streaming, like [XorCheckSum]: one character in, integer state, mask on the way out, so it can run
 * over a growing serial buffer. Also like [XorCheckSum], [add] takes a `Char` and not a byte - see
 * [CheckSumCalculator] for what that costs outside ASCII.
 */
class Crc16CheckSum : CheckSumCalculator {
    private var crc = 0

    override fun add(ch: Char) {
        crc = crc xor ((ch.code and 0xff) shl 8)
        for (bit in 0 until 8) {
            crc = if (crc and 0x8000 != 0) ((crc shl 1) xor POLYNOMIAL) and 0xffff else (crc shl 1) and 0xffff
        }
    }

    /**
     * The value, and the **five zero-padded decimal digits** section 8.4 requires as its lexeme.
     *
     * The padding is load-bearing rather than cosmetic: the digit count is what selects the algorithm
     * (section 8.1), so a CRC of 6939 emitted as four digits would be read back as an XOR checksum
     * and rejected, and one of 57 as three digits would be read as a *valid* XOR checksum of a
     * different line.
     */
    override fun get(): GInt {
        val value = crc and 0xffff
        return GInt(value, fiveDigits(value))
    }

    private fun fiveDigits(value: Int): String {
        val out = CharArray(5)
        var rest = value
        for (i in 4 downTo 0) {
            out[i] = ('0' + rest % 10)
            rest /= 10
        }
        return String(out)
    }

    private companion object {
        /** CCITT `x^16 + x^12 + x^5 + 1`, in its MSB-first encoding. */
        const val POLYNOMIAL = 0x1021
    }
}
