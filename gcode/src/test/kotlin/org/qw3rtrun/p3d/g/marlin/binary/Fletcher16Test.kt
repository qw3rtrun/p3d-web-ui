package org.qw3rtrun.p3d.g.marlin.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Known answers for the binary protocol's checksum.
 *
 * **Every expected value here was produced by running the reference implementation**, not by
 * reading it: `_build_checksum` was lifted verbatim out of `binproto2/protocols.py` in
 * `charleswillis3/marlin-binary-protocol` and run over each input, and the numbers below are its
 * output. That is the one test `SUMMARY.md` names as the highest-value one missing from `kmbp`,
 * where the only checksum assertion was a half-finished hand computation ending in "Let's just
 * trust the code for complex ones".
 */
class Fletcher16Test {

    /**
     * The inputs and the reference's answer for each. `0x6D24` is the one that also appears on the
     * wire: it is the header checksum of `sync=1, protocol/type=0x23, size=0`.
     */
    private val vectors: List<Pair<ByteArray, Int>> = listOf(
        byteArrayOf() to 0x0000,
        byteArrayOf(0x00) to 0x0000,
        byteArrayOf(0x01) to 0x0101,
        byteArrayOf(0x01, 0x02) to 0x0403,
        ascii("123456789") to 0x1EDE,
        ascii("abcde") to 0xC8F0,
        byteArrayOf(0xFF.toByte()) to 0x0000,
        byteArrayOf(0xFF.toByte(), 0xFF.toByte()) to 0x0000,
        byteArrayOf(0xFE.toByte()) to 0xFEFE,
        byteArrayOf(0xFE.toByte(), 0xFE.toByte()) to 0xFCFD,
        ByteArray(10) to 0x0000,
        byteArrayOf(0x01, 0x23, 0x00, 0x00) to 0x6D24,
        ByteArray(256) { it.toByte() } to 0x5500,
    )

    @Test
    fun `every reference vector matches, byte at a time`() {
        for ((bytes, expected) in vectors) {
            val cs = Fletcher16()
            for (b in bytes) cs.add(b.toInt())
            assertEquals(expected, cs.get()) { "differs for ${hex(bytes)}" }
        }
    }

    @Test
    fun `the range form agrees with the streaming form`() {
        for ((bytes, expected) in vectors) {
            assertEquals(expected, Fletcher16.of(bytes)) { "differs for ${hex(bytes)}" }
        }
    }

    @Test
    fun `a range of a larger buffer covers only that range`() {
        val padded = byteArrayOf(0x7F, 0x01, 0x23, 0x00, 0x00, 0x7F)
        assertEquals(0x6D24, Fletcher16.of(padded, 1, 5))
    }

    @Test
    fun `reset makes one instance reusable for one packet after another`() {
        val cs = Fletcher16()
        cs.add(byteArrayOf(0x01, 0x23, 0x00, 0x00), 0, 4)
        assertEquals(0x6D24, cs.get())
        cs.reset()
        assertEquals(0x0000, cs.get())
        cs.add(byteArrayOf(0x01, 0x02), 0, 2)
        assertEquals(0x0403, cs.get())
    }

    @Test
    fun `a sign-extended byte cannot poison the sums`() {
        // The whole reason add() masks: a Kotlin Byte of 0xFE is the Int -2, and -2 % 255 is -2.
        val cs = Fletcher16()
        cs.add(byteArrayOf(0xFE.toByte()), 0, 1)
        assertEquals(0xFEFE, cs.get())
    }

    /**
     * The algorithm's one surprising property, and the reason it is spelled out rather than
     * tidied: the sums are taken modulo 255, so the byte value 255 adds nothing at all.
     */
    @Nested
    inner class ModuloTwoFiftyFive {

        @Test
        fun `0xFF and 0x00 are the same byte to this checksum`() {
            assertEquals(Fletcher16.of(byteArrayOf(0x00)), Fletcher16.of(byteArrayOf(0xFF.toByte())))
            assertEquals(
                Fletcher16.of(byteArrayOf(0x12, 0x00, 0x34)),
                Fletcher16.of(byteArrayOf(0x12, 0xFF.toByte(), 0x34)),
            )
        }

        @Test
        fun `so a header for sync 0 and one for sync 255 carry the identical checksum`() {
            // Measured against the reference: _build_packet(0, 1, 0) and _build_packet(255, 1, 0)
            // differ only in the sync byte and agree on both checksum bytes.
            val zero = MbpCodec.encode(MbpPacket(0, MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY))
            val full = MbpCodec.encode(MbpPacket(255, MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY))
            assertEquals("ADB5001000001030", hex(zero))
            assertEquals("ADB5FF1000001030", hex(full))
        }

        @Test
        fun `the value never carries a byte of 0xFF in either half`() {
            // (x % 255) is 0..254, so both sums are, and 0xFF cannot appear in a checksum at all.
            var worst = 0
            for (length in 0..64) {
                val cs = Fletcher16()
                for (i in 0 until length) cs.add(0xFE)
                val value = cs.get()
                if (value > worst) worst = value
                assertEquals(0, if (value and 0xff == 0xff) 1 else 0) { "low byte 0xFF at $length" }
                assertEquals(0, if (value shr 8 == 0xff) 1 else 0) { "high byte 0xFF at $length" }
            }
            assertEquals(0xFEFE, worst)
        }
    }

    private fun ascii(text: String): ByteArray {
        val out = ByteArray(text.length)
        for (i in 0 until text.length) out[i] = text[i].code.toByte()
        return out
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789ABCDEF"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(digits[v shr 4]).append(digits[v and 0xf])
        }
        return out.toString()
    }
}
