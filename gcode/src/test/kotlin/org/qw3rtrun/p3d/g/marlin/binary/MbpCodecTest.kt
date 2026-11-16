package org.qw3rtrun.p3d.g.marlin.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Cover for the packet layout, against the reference rather than against itself.
 *
 * **Every expected byte string here is `_build_packet`'s output**, from `binproto2/protocols.py`
 * in `charleswillis3/marlin-binary-protocol`, run over the same arguments. The layout it produces
 * is the one `Marlin/src/feature/binary_stream.h` reads, so the two authorities agree and these
 * vectors pin both.
 *
 * The decode side is covered here only as a round trip; the framing itself - resynchronisation,
 * split buffers, corruption - is `MbpPacketReaderTest`.
 */
class MbpCodecTest {

    /**
     * The reference's output, verbatim. Each entry is what
     * `_build_packet(sync, protocol, type, data)` printed.
     */
    private val vectors: List<Pair<MbpPacket, String>> = listOf(
        MbpPacket(0, MbpProtocol.CONTROL, MbpControlType.SYNC) to
            "ADB5000100000103",
        MbpPacket(0, MbpProtocol.CONTROL, MbpControlType.CLOSE) to
            "ADB5000200000206",
        MbpPacket(0, MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY) to
            "ADB5001000001030",
        MbpPacket(1, MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY) to
            "ADB5011000001134",
        MbpPacket(255, MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY) to
            "ADB5FF1000001030",
        MbpPacket(2, MbpProtocol.FILE_TRANSFER, MbpTransferType.OPEN, open("test.gco", 0, 0)) to
            "ADB502110B001E510000746573742E67636F00B7E3",
        MbpPacket(3, MbpProtocol.FILE_TRANSFER, MbpTransferType.OPEN, open("a.g", 1, 1)) to
            "ADB5031106001A4B0101612E670078E5",
        MbpPacket(4, MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, ascii("hello")) to
            "ADB5041305001C5368656C6C6FA1FD",
        MbpPacket(0, MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY, byteArrayOf(0xAA.toByte(), 0xBB.toByte())) to
            "ADB5001002001234AABBBE72",
        MbpPacket(5, MbpProtocol.FILE_TRANSFER, MbpTransferType.CLOSE) to
            "ADB505120000174A",
        MbpPacket(6, MbpProtocol.FILE_TRANSFER, MbpTransferType.ABORT) to
            "ADB5061400001A54",
    )

    @Test
    fun `every reference vector is produced byte for byte`() {
        for ((packet, expected) in vectors) {
            assertEquals(expected, hex(MbpCodec.encode(packet))) { "differs for $packet" }
        }
    }

    @Test
    fun `and every one of them reads back as the packet it came from`() {
        for ((packet, expected) in vectors) {
            val frames = MbpPacketReader().frames(unhex(expected))
            assertEquals(listOf<MbpFrame>(packet), frames) { "round trip differs for $packet" }
        }
    }

    @Test
    fun `a 512-byte block matches the reference too`() {
        // The firmware's own receive buffer, so this is the widest block a stock build accepts.
        val packet = MbpPacket(7, MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, ByteArray(512))
        val bytes = MbpCodec.encode(packet)
        assertEquals(512 + MbpCodec.HEADER_SIZE + MbpCodec.FOOTER_SIZE, bytes.size)
        assertEquals("ADB5071300021C57", hex(bytes.copyOf(8)))
        assertEquals("8F3E", hex(bytes.copyOfRange(bytes.size - 2, bytes.size)))
        assertEquals(listOf<MbpFrame>(packet), MbpPacketReader().frames(bytes))
    }

    @Test
    fun `and so does a payload of every byte value`() {
        val payload = ByteArray(256) { it.toByte() }
        val packet = MbpPacket(8, MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, payload)
        val bytes = MbpCodec.encode(packet)
        assertEquals("ADB5081300011C5A", hex(bytes.copyOf(8)))
        assertEquals("920D", hex(bytes.copyOfRange(bytes.size - 2, bytes.size)))
    }

    @Test
    fun `a packet with no payload has no footer at all`() {
        // Not a detail: a receiver that always expected two trailing bytes would stall on every
        // control packet. The reference appends the footer only when len(data) is non-zero.
        assertEquals(MbpCodec.HEADER_SIZE, MbpCodec.encode(MbpPacket(0, 0, 1)).size)
        assertEquals(
            MbpCodec.HEADER_SIZE + 1 + MbpCodec.FOOTER_SIZE,
            MbpCodec.encode(MbpPacket(0, 0, 1, byteArrayOf(1))).size,
        )
    }

    @Test
    fun `decode of encode is the identity over every nibble and the sync id edges`() {
        val reader = MbpPacketReader()
        for (sync in intArrayOf(0, 1, 127, 128, 254, 255)) {
            for (protocol in 0..15) {
                for (type in 0..15) {
                    val payload = ByteArray(17) { (it * 13 + sync + protocol + type).toByte() }
                    val packet = MbpPacket(sync, protocol, type, payload)
                    assertEquals(
                        listOf<MbpFrame>(packet),
                        reader.frames(MbpCodec.encode(packet)),
                    ) { "round trip differs for $packet" }
                }
            }
        }
    }

    @Test
    fun `the range form writes the same bytes as the whole-array form`() {
        val file = ByteArray(40) { it.toByte() }
        val sliced = MbpCodec.encode(3, 1, 3, file, 8, 24)
        val copied = MbpCodec.encode(MbpPacket(3, 1, 3, file.copyOfRange(8, 24)))
        assertEquals(hex(copied), hex(sliced))
    }

    /**
     * The writing direction, where a bad argument is a mistake in the calling code rather than
     * something that arrived off a link - so it throws, unlike everything in `MbpFrame`.
     */
    @Nested
    inner class BadArguments {

        @Test
        fun `a range outside the array throws`() {
            val data = ByteArray(4)
            assertThrows(IllegalArgumentException::class.java) { MbpCodec.encode(0, 1, 3, data, 0, 5) }
            assertThrows(IllegalArgumentException::class.java) { MbpCodec.encode(0, 1, 3, data, -1, 4) }
            assertThrows(IllegalArgumentException::class.java) { MbpCodec.encode(0, 1, 3, data, 3, 2) }
        }

        @Test
        fun `a payload wider than the 16-bit size field throws`() {
            val data = ByteArray(MbpCodec.MAX_PAYLOAD + 1)
            assertThrows(IllegalArgumentException::class.java) { MbpCodec.encode(MbpPacket(0, 1, 3, data)) }
        }

        @Test
        fun `the widest payload the size field can describe does not`() {
            val data = ByteArray(MbpCodec.MAX_PAYLOAD)
            val bytes = MbpCodec.encode(MbpPacket(0, 1, 3, data))
            assertEquals("FFFF", hex(bytes.copyOfRange(4, 6)))
        }
    }

    /**
     * The packet model's own equality. `kmbp` hand-wrote these because the payload is a
     * `ByteArray`, and `SUMMARY.md`'s note stands: the test that matters is the one that differs.
     */
    @Nested
    inner class Equality {

        @Test
        fun `two packets with equal payload contents are equal`() {
            val one = MbpPacket(1, 2, 3, byteArrayOf(9, 8, 7))
            val two = MbpPacket(1, 2, 3, byteArrayOf(9, 8, 7))
            assertEquals(one, two)
            assertEquals(one.hashCode(), two.hashCode())
        }

        @Test
        fun `a different payload, sync, protocol or type is not equal`() {
            val base = MbpPacket(1, 2, 3, byteArrayOf(9, 8, 7))
            assertNotEquals(base, MbpPacket(1, 2, 3, byteArrayOf(9, 8, 6)))
            assertNotEquals(base, MbpPacket(1, 2, 3, byteArrayOf(9, 8)))
            assertNotEquals(base, MbpPacket(0, 2, 3, byteArrayOf(9, 8, 7)))
            assertNotEquals(base, MbpPacket(1, 0, 3, byteArrayOf(9, 8, 7)))
            assertNotEquals(base, MbpPacket(1, 2, 0, byteArrayOf(9, 8, 7)))
        }

        @Test
        fun `an empty payload equals the shared empty one`() {
            assertEquals(MbpPacket(1, 2, 3), MbpPacket(1, 2, 3, ByteArray(0)))
        }
    }

    private fun open(name: String, dummy: Int, compression: Int): ByteArray {
        val out = ByteArray(2 + name.length + 1)
        out[0] = dummy.toByte()
        out[1] = compression.toByte()
        for (i in 0 until name.length) out[2 + i] = name[i].code.toByte()
        return out
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

    private fun unhex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (i in 0 until out.size) out[i] = text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }
}
