package org.qw3rtrun.p3d.g.marlin.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Cover for the framing state machine: the buffer-boundary cases, and the three ways a packet
 * arrives broken.
 *
 * The rule the whole class exists to pin is that **nothing here throws**. A serial link delivers
 * noise, half packets and bytes in whatever sizes a read returns, so every one of those is a value
 * and the stream keeps going afterwards.
 */
class MbpPacketReaderTest {

    private val packet = MbpPacket(7, MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(1, 2, 3))

    private val bytes = MbpCodec.encode(packet)

    private val control = MbpPacket(0, MbpProtocol.CONTROL, MbpControlType.SYNC)

    @Test
    fun `a whole packet in one buffer reads as one frame`() {
        assertEquals(listOf<MbpFrame>(packet), MbpPacketReader().frames(bytes))
    }

    @Test
    fun `two packets back to back read as two`() {
        val both = bytes + MbpCodec.encode(control)
        assertEquals(listOf<MbpFrame>(packet, control), MbpPacketReader().frames(both))
    }

    /**
     * The case a decoder taking a whole array cannot do at all: a transport hands over whatever
     * bytes a read returned, never a packet boundary.
     */
    @Nested
    inner class SplitBuffers {

        @Test
        fun `a packet split at every possible point still reads as one`() {
            for (cut in 0..bytes.size) {
                val reader = MbpPacketReader()
                val first = reader.frames(bytes, 0, cut)
                val second = reader.frames(bytes, cut, bytes.size)
                assertEquals(listOf<MbpFrame>(packet), first + second) { "differs when cut at $cut" }
            }
        }

        @Test
        fun `a packet fed one byte at a time completes on its last byte and not before`() {
            val reader = MbpPacketReader()
            for (i in 0 until bytes.size - 1) {
                assertNull(reader.read(bytes[i].toInt())) { "a frame finished early, at byte $i" }
            }
            assertEquals(packet, reader.read(bytes[bytes.size - 1].toInt()))
        }

        @Test
        fun `an empty buffer finishes nothing`() {
            assertEquals(emptyList<MbpFrame>(), MbpPacketReader().frames(ByteArray(0)))
        }

        @Test
        fun `a buffer that ends mid-packet finishes nothing and loses nothing`() {
            val reader = MbpPacketReader()
            assertEquals(emptyList<MbpFrame>(), reader.frames(bytes, 0, bytes.size - 1))
            assertEquals(packet, reader.read(bytes[bytes.size - 1].toInt()))
        }
    }

    /**
     * Resynchronisation. The token is hunted for with a sliding two-byte window, exactly as
     * `BinaryStream::receive` does it, so anything that is not a packet is skipped rather than
     * fatal.
     */
    @Nested
    inner class Resynchronising {

        @Test
        fun `leading noise is skipped`() {
            val noisy = byteArrayOf(0x00, 0xFF.toByte(), 0x41, 0xAD.toByte(), 0x41) + bytes
            assertEquals(listOf<MbpFrame>(packet), MbpPacketReader().frames(noisy))
        }

        @Test
        fun `an ASCII reply in the middle of the stream is skipped`() {
            // What a host actually sees: the firmware answers in ASCII on the same link.
            val reply = ascii("ok7\nPFT:success\n")
            assertEquals(
                listOf<MbpFrame>(packet, control),
                MbpPacketReader().frames(bytes + reply + MbpCodec.encode(control)),
            )
        }

        @Test
        fun `a repeated token low byte does not lose the real token`() {
            // AD AD B5 ... - the first AD is noise and the window has to keep the second.
            assertEquals(
                listOf<MbpFrame>(packet),
                MbpPacketReader().frames(byteArrayOf(0xAD.toByte()) + bytes),
            )
        }

        @Test
        fun `a token high byte with no low byte before it is not a token`() {
            assertEquals(
                listOf<MbpFrame>(packet),
                MbpPacketReader().frames(byteArrayOf(0xB5.toByte(), 0xB5.toByte()) + bytes),
            )
        }

        @Test
        fun `a truncated packet costs the packet after it, and no more`() {
            // The cost of framing on a token rather than a length: a header cut short goes on
            // consuming bytes, so the *next* packet's token is eaten finishing it and that packet
            // is lost. The one after is read. The firmware has the same property and the same
            // answer - it asks for a resend - which is why this is pinned rather than papered over.
            val reader = MbpPacketReader()
            assertEquals(emptyList<MbpFrame>(), reader.frames(bytes, 0, 5))

            val good = MbpCodec.encode(control)
            val frames = reader.frames(good + good)
            assertEquals(2, frames.size)
            assertTrue(frames[0] is MbpHeaderCorrupt) { "expected a corrupt header, got ${frames[0]}" }
            assertEquals(control, frames[1])
        }

        @Test
        fun `reset throws away a half-read packet`() {
            val reader = MbpPacketReader()
            reader.frames(bytes, 0, 5)
            reader.reset()
            assertEquals(listOf<MbpFrame>(packet), reader.frames(bytes))
        }
    }

    /**
     * The three failures, as values. Each carries what arrived, so a caller can count it and ask
     * for the packet again - which is what the firmware itself does with `rs`.
     */
    @Nested
    inner class Corruption {

        @Test
        fun `a corrupted sync byte is reported as a corrupt header`() {
            val broken = bytes.copyOf()
            broken[2] = (broken[2] + 1).toByte()
            val frames = MbpPacketReader().frames(broken)
            assertEquals(1, frames.size)
            val frame = frames[0]
            assertTrue(frame is MbpHeaderCorrupt) { "expected a corrupt header, got $frame" }
            frame as MbpHeaderCorrupt
            // The reference's _build_checksum over [07,13,03,00] and over [08,13,03,00].
            assertEquals(8, frame.sync)
            assertEquals(0x5B1D, frame.declared)
            assertEquals(0x5F1E, frame.computed)
        }

        @Test
        fun `a corrupted header checksum field is too`() {
            val broken = bytes.copyOf()
            broken[6] = (broken[6].toInt() xor 0xFF).toByte()
            val frames = MbpPacketReader().frames(broken)
            assertEquals(1, frames.size)
            assertTrue(frames[0] is MbpHeaderCorrupt) { "got ${frames[0]}" }
        }

        @Test
        fun `a corrupted payload byte is reported as a corrupt payload, with the header intact`() {
            val broken = bytes.copyOf()
            broken[MbpCodec.HEADER_SIZE] = (broken[MbpCodec.HEADER_SIZE].toInt() xor 0xFF).toByte()
            val frames = MbpPacketReader().frames(broken)
            assertEquals(1, frames.size)
            val frame = frames[0]
            assertTrue(frame is MbpPayloadCorrupt) { "expected a corrupt payload, got $frame" }
            frame as MbpPayloadCorrupt
            assertEquals(7, frame.sync)
            assertEquals(MbpProtocol.FILE_TRANSFER, frame.protocol)
            assertEquals(MbpTransferType.WRITE, frame.type)
        }

        @Test
        fun `a corrupted footer is too`() {
            val broken = bytes.copyOf()
            broken[broken.size - 1] = (broken[broken.size - 1].toInt() xor 0xFF).toByte()
            assertTrue(MbpPacketReader().frames(broken)[0] is MbpPayloadCorrupt)
        }

        @Test
        fun `a payload wider than the reader holds is reported rather than overrunning the buffer`() {
            val wide = MbpCodec.encode(MbpPacket(3, 1, 3, ByteArray(300)))
            val frames = MbpPacketReader(limit = 64).frames(wide)
            assertEquals(listOf<MbpFrame>(MbpPayloadTooLarge(3, 300, 64)), frames)
        }

        @Test
        fun `the stream keeps going after each of them`() {
            // The property that makes errors-as-values worth having: one bad packet does not end
            // the session, so the next one is still read.
            val good = MbpCodec.encode(control)
            for (at in intArrayOf(2, 6, MbpCodec.HEADER_SIZE, bytes.size - 1)) {
                val broken = bytes.copyOf()
                broken[at] = (broken[at].toInt() xor 0xFF).toByte()
                val frames = MbpPacketReader().frames(broken + good)
                assertTrue(frames.contains(control)) { "lost the next packet after a flip at $at" }
            }
        }

        @Test
        fun `the checksum cannot tell a payload of 0x00 from one of 0xFF`() {
            // Not a reader defect - the consequence of Fletcher-16 modulo 255, pinned here so that
            // nobody "fixes" the reader when they meet it. The firmware has the same blind spot.
            val zero = MbpCodec.encode(MbpPacket(1, 1, 3, ByteArray(4)))
            val flipped = zero.copyOf()
            for (i in 0 until 4) flipped[MbpCodec.HEADER_SIZE + i] = 0xFF.toByte()
            val frames = MbpPacketReader().frames(flipped)
            assertEquals(
                listOf<MbpFrame>(MbpPacket(1, 1, 3, ByteArray(4) { 0xFF.toByte() })),
                frames,
            )
        }
    }

    private fun ascii(text: String): ByteArray {
        val out = ByteArray(text.length)
        for (i in 0 until text.length) out[i] = text[i].code.toByte()
        return out
    }
}
