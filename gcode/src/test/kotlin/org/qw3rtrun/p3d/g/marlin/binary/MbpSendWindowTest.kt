package org.qw3rtrun.p3d.g.marlin.binary

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder
import org.qw3rtrun.p3d.g.protocol.SimpleOkRs

/**
 * Cover for the sync-id correlation - the part `SUMMARY.md` names as the one to redesign first.
 *
 * The bug being tested against: the Python reference and `kmbp` both tracked the stream with one
 * `packet_status` flag and a shared FIFO of replies, so any reply satisfied whichever request was
 * waiting. Their handshake sends an ASCII `M28 B1` and then a binary `SYNC` packet, and the two
 * answers - a bare `ok` and an `ss,...` - can arrive in either order; in one of the two the `ok`
 * was consumed by the wrong waiter and the handshake retransmitted until it timed out. The tests
 * below drive both orders and assert the window reaches the same place.
 */
class MbpSendWindowTest {

    @Test
    fun `a fresh window starts at sync zero with nothing in flight`() {
        val window = MbpSendWindow()
        assertEquals(0, window.next)
        assertFalse(window.inFlight)
        assertFalse(window.synchronized)
        assertEquals(0, window.errors)
        assertEquals(0, window.buffer)
        assertEquals("", window.version)
    }

    @Test
    fun `the binary-mode switch is an ASCII command with the flag first`() {
        // M28's B flag is read from the start of its string argument, so it cannot follow a
        // filename - and StartSDWrite, whose generated model reads everything after M28 as one
        // bare filename, would encode "B1" as the name of a file.
        assertEquals("M28 B1", GEncoder.encode(MbpSendWindow.enterBinaryMode()))
    }

    /** The handshake, in both orders, which is the whole point of the class. */
    @Nested
    inner class Handshake {

        @Test
        fun `ss completes the SYNC packet and adopts all three fields`() {
            val window = MbpSendWindow()
            assertArrayEquals(
                MbpCodec.encode(MbpPacket(0, MbpProtocol.CONTROL, MbpControlType.SYNC)),
                window.resync(),
            )
            assertTrue(window.inFlight)

            assertEquals(MbpSynced(4, 96, "0.1.0"), window.on(MbpStreamSync(4, 96, "0.1.0")))
            assertEquals(4, window.next)
            assertEquals(96, window.buffer)
            assertEquals(96, window.blockSize)
            assertEquals("0.1.0", window.version)
            assertTrue(window.synchronized)
            assertFalse(window.inFlight)
        }

        @Test
        fun `the ASCII ok arriving first changes nothing, because it is not an MbpRs`() {
            // The structural half of the fix: a bare ok carries no sync id, so it cannot reach
            // this window at all. MarlinRsDecoder reads it, MbpRsDecoder declines it.
            assertEquals(SimpleOkRs, MarlinRsDecoder.decode("ok"))
            assertNull(MbpRsDecoder.decode("ok"))

            val window = MbpSendWindow()
            window.resync()
            assertTrue(window.inFlight)
            assertEquals(MbpSynced(0, 512, "0.1.0"), window.on(MbpStreamSync(0, 512, "0.1.0")))
            assertTrue(window.synchronized)
        }

        @Test
        fun `the handshake lands identically whichever order ok and ss arrive in`() {
            // The bug in both prior implementations, driven as a host actually drives it: decode
            // the line, and feed the window only what it is about.
            val okFirst = MbpSendWindow()
            okFirst.resync()
            feed(okFirst, "ok")
            feed(okFirst, "ss0,512,0.1.0")

            val ssFirst = MbpSendWindow()
            ssFirst.resync()
            feed(ssFirst, "ss0,512,0.1.0")
            feed(ssFirst, "ok")

            for (window in listOf(okFirst, ssFirst)) {
                assertTrue(window.synchronized) { "the handshake did not land" }
                assertFalse(window.inFlight) { "the SYNC packet is still outstanding" }
                assertEquals(0, window.next)
                assertEquals(512, window.buffer)
                assertEquals(0, window.errors) { "a retransmit happened that should not have" }
            }
        }

        @Test
        fun `an ok for the SYNC packet is out of sync, because SYNC is answered by ss alone`() {
            // The firmware answers a SYNC control packet while it is still reading the header and
            // never sends an ok for it. A window that accepted one here would advance its sync id
            // past the firmware's.
            val window = MbpSendWindow()
            window.resync()
            assertEquals(MbpOutOfSync(0, 0), window.on(MbpOk(0)))
        }

        @Test
        fun `a smaller firmware buffer lowers the block size, a larger one does not raise it`() {
            val small = MbpSendWindow(blockSize = 512)
            small.on(MbpStreamSync(0, 96, "0.1.0"))
            assertEquals(96, small.blockSize)

            val large = MbpSendWindow(blockSize = 256)
            large.on(MbpStreamSync(0, 4096, "0.1.0"))
            assertEquals(256, large.blockSize)
            assertEquals(4096, large.buffer)
        }
    }

    /** Stamping and acknowledging, one packet at a time. */
    @Nested
    inner class OnePacketInFlight {

        @Test
        fun `a send stamps the next sync id and holds the bytes`() {
            val window = MbpSendWindow()
            val bytes = window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY, MbpPacket.EMPTY)
            assertArrayEquals(
                MbpCodec.encode(MbpPacket(0, MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY)),
                bytes,
            )
            assertTrue(window.inFlight)
            assertEquals(0, window.next)
        }

        @Test
        fun `a second send is refused and consumes no sync id`() {
            val window = MbpSendWindow()
            assertNotNull(window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY, MbpPacket.EMPTY))
            assertNull(window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.ABORT, MbpPacket.EMPTY))

            assertEquals(MbpAccepted, window.on(MbpOk(0)))
            assertEquals(1, window.next)
            val second = window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.ABORT, MbpPacket.EMPTY)
            assertArrayEquals(
                MbpCodec.encode(MbpPacket(1, MbpProtocol.FILE_TRANSFER, MbpTransferType.ABORT)),
                second,
            )
        }

        @Test
        fun `the sync id wraps at 256`() {
            val window = MbpSendWindow()
            window.on(MbpStreamSync(254, 512, "0.1.0"))
            for (expected in intArrayOf(254, 255, 0, 1)) {
                assertEquals(expected, window.next)
                window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(1))
                assertEquals(MbpAccepted, window.on(MbpOk(expected)))
            }
            assertEquals(2, window.next)
        }

        @Test
        fun `an ok for a sync id we are not waiting on does not complete anything`() {
            // The Python reference raises SynchronizationError here; kmbp completed whatever was
            // waiting. This answers with a value and leaves the packet outstanding.
            val window = MbpSendWindow()
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY, MbpPacket.EMPTY)
            assertEquals(MbpOutOfSync(0, 9), window.on(MbpOk(9)))
            assertTrue(window.inFlight)
            assertEquals(0, window.next)
            assertEquals(MbpAccepted, window.on(MbpOk(0)))
        }

        @Test
        fun `an ok with nothing in flight is out of sync and says so`() {
            assertEquals(MbpOutOfSync(-1, 3), MbpSendWindow().on(MbpOk(3)))
        }

        @Test
        fun `a payload wider than the negotiated block size throws`() {
            // The writing direction: the caller built this packet, so failing at the call site is
            // the point.
            val window = MbpSendWindow()
            window.on(MbpStreamSync(0, 96, "0.1.0"))
            assertThrows(IllegalArgumentException::class.java) {
                window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, ByteArray(97))
            }
            assertNotNull(window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, ByteArray(96)))
        }
    }

    /** Loss and corruption: what the firmware asks for, and what a timeout costs. */
    @Nested
    inner class Recovery {

        @Test
        fun `a resend for the outstanding packet hands back the exact bytes that were sent`() {
            val window = MbpSendWindow()
            val sent = window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(1, 2, 3))
            val ack = window.on(MbpResend(0))
            assertTrue(ack is MbpResendNeeded) { "got $ack" }
            ack as MbpResendNeeded
            assertArrayEquals(sent, ack.bytes)
            assertEquals(1, window.errors)
            assertTrue(window.inFlight)
        }

        @Test
        fun `a resend naming a different sync id means the two ends diverged`() {
            // Not a retransmit: the firmware is waiting for a packet we are not sending, so the
            // only way forward is a fresh SYNC. The reference retransmits and loops.
            val window = MbpSendWindow()
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(1))
            assertEquals(MbpOutOfSync(0, 5), window.on(MbpResend(5)))
            assertEquals(1, window.errors)
        }

        @Test
        fun `a timeout retransmit hands back the same bytes and counts an error`() {
            val window = MbpSendWindow()
            val sent = window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(1))
            assertArrayEquals(sent, window.retransmit())
            assertEquals(1, window.errors)
            assertArrayEquals(sent, window.retransmit())
            assertEquals(2, window.errors)
            assertEquals(MbpAccepted, window.on(MbpOk(0)))
        }

        /**
         * The retransmit's own answer, arriving after the original's. Measured 2026-11-26 against
         * MarlinSim behind a proxy that froze the link for 3s: packet 175 went out, was sent again
         * at the 2s timeout, and when the link thawed the firmware answered `ok175` for each copy -
         * the second after the host had already sent 176. Reading that second one as out of sync
         * ended a 4.6 MB transfer at 698 KB, over a stall shorter than every timeout.
         */
        @Test
        fun `the duplicate ok a retransmit earns is not ours and leaves the next packet waiting`() {
            val window = MbpSendWindow()
            window.on(MbpStreamSync(175, 96, "0.1.0"))
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(1))
            window.retransmit()
            assertEquals(MbpAccepted, window.on(MbpOk(175)))
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(2))

            assertEquals(MbpNotOurs, window.on(MbpOk(175)))
            assertTrue(window.inFlight, "176 is still waiting for its own ok")
            assertEquals(MbpAccepted, window.on(MbpOk(176)))
        }

        @Test
        fun `only the id just accepted counts as a duplicate, an older one is still out of sync`() {
            val window = MbpSendWindow()
            window.on(MbpStreamSync(10, 96, "0.1.0"))
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(1))
            window.on(MbpOk(10))
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(2))
            window.on(MbpOk(11))
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(3))

            assertEquals(MbpOutOfSync(12, 10), window.on(MbpOk(10)))
        }

        @Test
        fun `a handshake forgets the last accepted id, so an ok for it is out of sync again`() {
            val window = MbpSendWindow()
            window.on(MbpStreamSync(4, 96, "0.1.0"))
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(1))
            window.on(MbpOk(4))
            window.resync()
            window.on(MbpStreamSync(5, 96, "0.1.0"))
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(2))

            assertEquals(MbpOutOfSync(5, 4), window.on(MbpOk(4)))
        }

        @Test
        fun `nothing in flight means nothing to retransmit`() {
            assertNull(MbpSendWindow().retransmit())
            assertEquals(0, MbpSendWindow().errors)
        }

        @Test
        fun `a fatal error desynchronises the stream and clears the window`() {
            val window = MbpSendWindow()
            window.on(MbpStreamSync(0, 512, "0.1.0"))
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, byteArrayOf(1))
            assertEquals(MbpFatal(0), window.on(MbpFatalError(0)))
            assertFalse(window.synchronized)
            assertFalse(window.inFlight)
            assertEquals(1, window.errors)

            // and resync is the way back
            assertNotNull(window.resync())
            window.on(MbpStreamSync(0, 512, "0.1.0"))
            assertTrue(window.synchronized)
        }

        @Test
        fun `a PFT line is not this window's business`() {
            val window = MbpSendWindow()
            window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.OPEN, byteArrayOf(0, 0, 65, 0))
            assertEquals(MbpNotOurs, window.on(MbpTransferSuccess))
            assertEquals(MbpNotOurs, window.on(MbpTransferVersion("0.1.0", "none", 0, 0)))
            assertTrue(window.inFlight)
        }
    }

    @Test
    fun `the close control packet is acknowledged like any other`() {
        // Unlike SYNC: CLOSE goes through the firmware's normal dispatch, which writes the ok
        // before it acts on the packet.
        val window = MbpSendWindow()
        window.on(MbpStreamSync(3, 512, "0.1.0"))
        assertArrayEquals(
            MbpCodec.encode(MbpPacket(3, MbpProtocol.CONTROL, MbpControlType.CLOSE)),
            window.close(),
        )
        assertEquals(MbpAccepted, window.on(MbpOk(3)))
        assertEquals(4, window.next)
    }

    /** One received line, driven the way a host drives it: decode, and route only what fits. */
    private fun feed(window: MbpSendWindow, line: String) {
        val reply = MbpRsDecoder.decode(line) ?: return
        window.on(reply)
    }
}
