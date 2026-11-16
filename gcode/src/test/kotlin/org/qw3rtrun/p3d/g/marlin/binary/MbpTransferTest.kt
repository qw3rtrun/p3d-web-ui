package org.qw3rtrun.p3d.g.marlin.binary

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Cover for the file-transfer sequencing: the whole session, and every way it can end early.
 *
 * The session below is the one `binary_stream.h` runs, and the order of the replies matters: the
 * firmware writes the packet's `ok` *before* it dispatches the packet, so an `OPEN` is answered
 * `ok` and then `PFT:success`. Every test drives the state machine with that order.
 *
 * **The packets are checked by decoding them**, with [MbpPacketReader], rather than by comparing
 * byte strings - so each assertion says which packet went out and with what payload, and the codec
 * vectors in `MbpCodecTest` are what pins the bytes themselves.
 */
class MbpTransferTest {

    private val data = ByteArray(1100) { (it and 0xff).toByte() }

    private fun synced(blockSize: Int = 512): MbpSendWindow {
        val window = MbpSendWindow(blockSize)
        window.on(MbpStreamSync(0, 4096, "0.1.0"))
        return window
    }

    @Test
    fun `a whole session writes every byte of the file and closes it`() {
        val window = synced()
        val transfer = MbpTransfer(window, "test.gco", data)
        assertEquals(3, transfer.blocks)
        assertEquals(512, transfer.blockSize)

        assertEquals(packet(0, MbpTransferType.QUERY), sent(transfer.start()))
        assertEquals(MbpIdle, transfer.on(MbpOk(0)))
        assertEquals(MbpTransferState.QUERY, transfer.state)

        val version = MbpTransferVersion("0.1.0", "none", 0, 0)
        assertEquals(open("test.gco", 1, dummy = false), sent(transfer.on(version)))
        assertEquals("none", transfer.clientCompression)
        assertEquals(MbpIdle, transfer.on(MbpOk(1)))
        assertEquals(MbpTransferState.OPENING, transfer.state)

        assertEquals(block(2, 0, 512), sent(transfer.on(MbpTransferSuccess)))
        assertEquals(MbpTransferState.WRITING, transfer.state)
        assertEquals(0, transfer.blocksWritten)
        assertEquals(0, transfer.percent())

        assertEquals(block(3, 512, 1024), sent(transfer.on(MbpOk(2))))
        assertEquals(1, transfer.blocksWritten)
        assertEquals(33, transfer.percent())

        assertEquals(block(4, 1024, 1100), sent(transfer.on(MbpOk(3))))
        assertEquals(66, transfer.percent())

        assertEquals(packet(5, MbpTransferType.CLOSE), sent(transfer.on(MbpOk(4))))
        assertEquals(MbpTransferState.CLOSING, transfer.state)
        assertEquals(3, transfer.blocksWritten)
        assertEquals(1100, transfer.bytesWritten)
        assertEquals(100, transfer.percent())

        assertEquals(MbpIdle, transfer.on(MbpOk(5)))
        assertEquals(MbpFinished, transfer.on(MbpTransferSuccess))
        assertEquals(MbpTransferState.DONE, transfer.state)
        assertEquals(0, window.errors)
    }

    @Test
    fun `the last block is short and carries only the bytes that are left`() {
        val transfer = MbpTransfer(synced(), "a.g", data)
        drive(transfer)
        sent(transfer.on(MbpOk(2)))
        val last = sent(transfer.on(MbpOk(3)))
        assertEquals(1100 - 1024, last.payload.size)
        assertArrayEquals(data.copyOfRange(1024, 1100), last.payload)
    }

    @Test
    fun `a file that is an exact multiple of the block size needs no short block`() {
        val transfer = MbpTransfer(synced(blockSize = 100), "a.g", ByteArray(300))
        assertEquals(3, transfer.blocks)
        drive(transfer)
        assertEquals(100, sent(transfer.on(MbpOk(2))).payload.size)
        assertEquals(100, sent(transfer.on(MbpOk(3))).payload.size)
        assertEquals(packet(5, MbpTransferType.CLOSE), sent(transfer.on(MbpOk(4))))
    }

    @Test
    fun `an empty file opens and closes with no write at all`() {
        // The reference divided by the file length to report a compression ratio, so an empty file
        // was a division by zero before it got this far.
        val transfer = MbpTransfer(synced(), "empty.gco", ByteArray(0))
        assertEquals(0, transfer.blocks)
        assertEquals(100, transfer.percent())

        transfer.start()
        transfer.on(MbpOk(0))
        transfer.on(MbpTransferVersion("0.1.0", "none", 0, 0))
        transfer.on(MbpOk(1))
        assertEquals(packet(2, MbpTransferType.CLOSE), sent(transfer.on(MbpTransferSuccess)))
        transfer.on(MbpOk(2))
        assertEquals(MbpFinished, transfer.on(MbpTransferSuccess))
    }

    @Test
    fun `the block size the window negotiated is the one that is used`() {
        val window = MbpSendWindow(512)
        window.on(MbpStreamSync(0, 96, "0.1.0"))
        val transfer = MbpTransfer(window, "a.g", data)
        assertEquals(96, transfer.blockSize)
        assertEquals(12, transfer.blocks)
        drive(transfer)
        assertEquals(96, sent(transfer.on(MbpOk(2))).payload.size)
    }

    /** The `OPEN` payload, which is the one packet with a structure of its own. */
    @Nested
    inner class OpenPayload {

        @Test
        fun `it is two flag bytes, the name, and a null terminator`() {
            // The firmware validates `length > sizeof(Open) && buffer[length - 1] == 0`, so the
            // terminator is not decoration - without it the open is answered PFT:fail.
            val transfer = MbpTransfer(synced(), "test.gco", data)
            transfer.start()
            transfer.on(MbpOk(0))
            val out = sent(transfer.on(MbpTransferVersion("0.1.0", "none", 0, 0)))
            assertArrayEquals(
                byteArrayOf(
                    0, 0, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(),
                    't'.code.toByte(), '.'.code.toByte(), 'g'.code.toByte(), 'c'.code.toByte(),
                    'o'.code.toByte(), 0,
                ),
                out.payload,
            )
        }

        @Test
        fun `the dummy flag is the first byte, for a link benchmark that writes nothing`() {
            val transfer = MbpTransfer(synced(), "a.g", data, dummy = true)
            transfer.start()
            transfer.on(MbpOk(0))
            val out = sent(transfer.on(MbpTransferVersion("0.1.0", "none", 0, 0)))
            assertEquals(1, out.payload[0].toInt())
        }

        @Test
        fun `the compression flag is always clear, because no compressor is ported`() {
            // The client advertises heatshrink and the transfer still opens uncompressed. Asking
            // for compression this module cannot perform would write a corrupt file.
            val transfer = MbpTransfer(synced(), "a.g", data)
            transfer.start()
            transfer.on(MbpOk(0))
            val out = sent(transfer.on(MbpTransferVersion("0.1.0", "heatshrink", 8, 4)))
            assertEquals(0, out.payload[1].toInt())
            assertEquals("heatshrink", transfer.clientCompression)
            assertEquals(8, transfer.clientWindow)
            assertEquals(4, transfer.clientLookahead)
        }

        @Test
        fun `a name that is not printable ASCII is refused at construction`() {
            // The writing direction, and the payload carries one byte per character, so a name
            // outside seven-bit ASCII cannot be written at all. Failing where the caller named the
            // file beats failing three replies later with PFT:fail.
            assertThrows(IllegalArgumentException::class.java) {
                MbpTransfer(synced(), "café.gco", data)
            }
            assertThrows(IllegalArgumentException::class.java) {
                MbpTransfer(synced(), "a\tb.gco", data)
            }
            // A blank is printable, and LONG_FILENAME_HOST_SUPPORT builds write names with them.
            assertEquals("a b.gco", MbpTransfer(synced(), "a b.gco", data).name)
        }

        @Test
        fun `an empty name is refused at construction`() {
            assertThrows(IllegalArgumentException::class.java) {
                MbpTransfer(synced(), "", data)
            }
        }
    }

    /** The recovery paths, one per `PFT:` line that is not a success. */
    @Nested
    inner class WhenItGoesWrong {

        @Test
        fun `a busy client is aborted and the open retried once`() {
            // What an interrupted transfer leaves behind. The firmware would also time it out by
            // itself after ten seconds.
            val transfer = MbpTransfer(synced(), "a.g", data)
            transfer.start()
            transfer.on(MbpOk(0))
            transfer.on(MbpTransferVersion("0.1.0", "none", 0, 0))
            transfer.on(MbpOk(1))

            assertEquals(packet(2, MbpTransferType.ABORT), sent(transfer.on(MbpTransferBusy)))
            assertEquals(MbpTransferState.ABORTING, transfer.state)
            transfer.on(MbpOk(2))
            assertEquals(open("a.g", 3, dummy = false), sent(transfer.on(MbpTransferSuccess)))
            assertEquals(MbpTransferState.OPENING, transfer.state)
            transfer.on(MbpOk(3))
            assertEquals(block(4, 0, 512), sent(transfer.on(MbpTransferSuccess)))
        }

        @Test
        fun `a client that stays busy gives up rather than looping`() {
            val transfer = MbpTransfer(synced(), "a.g", data)
            transfer.start()
            transfer.on(MbpOk(0))
            transfer.on(MbpTransferVersion("0.1.0", "none", 0, 0))
            transfer.on(MbpOk(1))
            transfer.on(MbpTransferBusy)
            transfer.on(MbpOk(2))
            transfer.on(MbpTransferSuccess)
            transfer.on(MbpOk(3))

            assertEquals(
                MbpGaveUp("the client stayed busy after an abort"),
                transfer.on(MbpTransferBusy),
            )
            assertEquals(MbpTransferState.GAVE_UP, transfer.state)
        }

        @Test
        fun `each failing PFT line gives up with its own reason`() {
            assertEquals(MbpGaveUp("the client cannot open a.g"), opening().on(MbpTransferFail))
            assertEquals(
                MbpGaveUp("the client's storage reported an IO error"),
                opening().on(MbpTransferIoError),
            )
            assertEquals(MbpGaveUp("the client has no open file"), opening().on(MbpTransferInvalid))
            assertEquals(
                MbpGaveUp("the client does not know this packet type"),
                opening().on(MbpUnknownPacketType),
            )
        }

        @Test
        fun `a write to a client with no open file is not treated as advisory`() {
            // PFT:invalid on a WRITE means the block went nowhere, so the file on the card is
            // already wrong and there is nothing to retry.
            val transfer = MbpTransfer(synced(), "a.g", data)
            drive(transfer)
            assertEquals(MbpTransferState.WRITING, transfer.state)
            assertEquals(MbpGaveUp("the client has no open file"), transfer.on(MbpTransferInvalid))
        }

        @Test
        fun `a resend request hands the packet back for another transmission`() {
            val transfer = MbpTransfer(synced(), "a.g", data)
            drive(transfer)
            val step = transfer.on(MbpResend(2))
            assertTrue(step is MbpTransmit) { "got $step" }
            assertEquals(block(2, 0, 512), read((step as MbpTransmit).bytes))
            assertEquals(MbpTransferState.WRITING, transfer.state)
            assertEquals(0, transfer.blocksWritten)
        }

        @Test
        fun `a fatal error ends the transfer, because the stream has to be resynchronised`() {
            val transfer = MbpTransfer(synced(), "a.g", data)
            drive(transfer)
            assertEquals(MbpGaveUp("fatal error on packet 2"), transfer.on(MbpFatalError(2)))
            assertEquals(MbpTransferState.GAVE_UP, transfer.state)
        }

        @Test
        fun `an ok for the wrong packet ends it rather than counting a block that is not in`() {
            // The failure mode the reference's shared FIFO had: any ok advanced the transfer, so a
            // stale one made the block counter run ahead of what the card actually holds.
            val transfer = MbpTransfer(synced(), "a.g", data)
            drive(transfer)
            assertEquals(MbpGaveUp("out of sync: waiting on 2, got 9"), transfer.on(MbpOk(9)))
            assertEquals(0, transfer.blocksWritten)
        }

        @Test
        fun `once it has given up, every further reply says so and changes nothing`() {
            val transfer = MbpTransfer(synced(), "a.g", data)
            drive(transfer)
            transfer.on(MbpTransferIoError)
            for (reply in listOf<MbpRs<*>>(MbpOk(3), MbpTransferSuccess, MbpResend(3))) {
                assertEquals(
                    MbpGaveUp("the client's storage reported an IO error"),
                    transfer.on(reply),
                )
            }
            assertEquals(0, transfer.blocksWritten)
        }

        @Test
        fun `and once it is done, every further reply says that`() {
            val transfer = MbpTransfer(synced(), "a.g", ByteArray(0))
            transfer.start()
            transfer.on(MbpOk(0))
            transfer.on(MbpTransferVersion("0.1.0", "none", 0, 0))
            transfer.on(MbpOk(1))
            transfer.on(MbpTransferSuccess)
            transfer.on(MbpOk(2))
            assertEquals(MbpFinished, transfer.on(MbpTransferSuccess))
            assertEquals(MbpFinished, transfer.on(MbpOk(3)))
        }

        @Test
        fun `start twice gives up rather than sending a second query`() {
            val transfer = MbpTransfer(synced(), "a.g", data)
            transfer.start()
            assertEquals(MbpGaveUp("already started"), transfer.start())
        }
    }

    /**
     * A cancel is an `ABORT` too, but it must not end up reopening the file - which is why it has
     * its own state.
     */
    @Nested
    inner class Cancelling {

        @Test
        fun `a cancel sends abort and then finishes, with the caller's reason`() {
            val transfer = MbpTransfer(synced(), "a.g", data)
            drive(transfer)
            transfer.on(MbpOk(2))

            assertEquals(MbpTransferState.WRITING, transfer.state)
            assertEquals(packet(3, MbpTransferType.ABORT), sent(transfer.abort("the operator cancelled")))
            assertEquals(MbpTransferState.CANCELLING, transfer.state)
            assertEquals(MbpIdle, transfer.on(MbpOk(3)))
            assertEquals(MbpGaveUp("the operator cancelled"), transfer.on(MbpTransferSuccess))
            assertEquals(MbpTransferState.GAVE_UP, transfer.state)
        }

        @Test
        fun `the abandoned block's sync id is reused, so the stream stays in step`() {
            // A write is in flight for almost all of a transfer, so a cancel has to displace it.
            // Advancing past its id instead would leave a gap and the ABORT would be answered rs.
            val window = synced()
            val transfer = MbpTransfer(window, "a.g", data)
            drive(transfer)
            assertTrue(window.inFlight)
            assertEquals(packet(2, MbpTransferType.ABORT), sent(transfer.abort("cancelled")))
            assertEquals(2, window.next)
        }

        @Test
        fun `a cancel does not reopen the file the way a busy abort does`() {
            val transfer = MbpTransfer(synced(), "a.g", data)
            drive(transfer)
            transfer.abort("cancelled")
            transfer.on(MbpOk(2))
            assertEquals(MbpGaveUp("cancelled"), transfer.on(MbpTransferSuccess))
        }
    }

    /**
     * Progress, which the reference got wrong in two ways at once: `i / blocks` never reached 100,
     * so a second hard-coded 100% line was printed to cover it, and the formatting went through a
     * locale-dependent `String.format`.
     */
    @Nested
    inner class Progress {

        @Test
        fun `it reaches exactly 100 on the last block and not before`() {
            val transfer = MbpTransfer(synced(blockSize = 100), "a.g", ByteArray(400))
            drive(transfer)
            assertEquals(0, transfer.percent())
            val seen = ArrayList<Int>()
            for (sync in 2..5) {
                transfer.on(MbpOk(sync))
                seen.add(transfer.percent())
            }
            assertEquals(listOf(25, 50, 75, 100), seen)
        }

        @Test
        fun `bytes written counts whole blocks, and the real file size at the end`() {
            val transfer = MbpTransfer(synced(blockSize = 512), "a.g", data)
            drive(transfer)
            assertEquals(0, transfer.bytesWritten)
            transfer.on(MbpOk(2))
            assertEquals(512, transfer.bytesWritten)
            transfer.on(MbpOk(3))
            assertEquals(1024, transfer.bytesWritten)
            transfer.on(MbpOk(4))
            assertEquals(1100, transfer.bytesWritten)
        }

        @Test
        fun `an empty file is complete before it starts`() {
            assertEquals(100, MbpTransfer(synced(), "a.g", ByteArray(0)).percent())
        }
    }

    /** Takes a transfer to the point where the `OPEN` has been acknowledged. */
    private fun opening(): MbpTransfer {
        val transfer = MbpTransfer(synced(), "a.g", data)
        transfer.start()
        transfer.on(MbpOk(0))
        transfer.on(MbpTransferVersion("0.1.0", "none", 0, 0))
        transfer.on(MbpOk(1))
        return transfer
    }

    /** The same, one step further: the first write packet is out and awaiting its `ok`. */
    private fun drive(transfer: MbpTransfer) {
        transfer.start()
        transfer.on(MbpOk(0))
        transfer.on(MbpTransferVersion("0.1.0", "none", 0, 0))
        transfer.on(MbpOk(1))
        transfer.on(MbpTransferSuccess)
    }

    private fun sent(step: MbpStep): MbpPacket {
        assertTrue(step is MbpTransmit) { "expected bytes to transmit, got $step" }
        return read((step as MbpTransmit).bytes)
    }

    private fun read(bytes: ByteArray): MbpPacket {
        val frames = MbpPacketReader().frames(bytes)
        assertEquals(1, frames.size) { "expected one packet, read $frames" }
        val frame = frames[0]
        assertTrue(frame is MbpPacket) { "expected a packet, read $frame" }
        return frame as MbpPacket
    }

    private fun packet(sync: Int, type: Int): MbpPacket =
        MbpPacket(sync, MbpProtocol.FILE_TRANSFER, type)

    private fun block(sync: Int, from: Int, to: Int): MbpPacket =
        MbpPacket(sync, MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, data.copyOfRange(from, to))

    private fun open(name: String, sync: Int, dummy: Boolean): MbpPacket {
        val payload = ByteArray(2 + name.length + 1)
        payload[0] = if (dummy) 1 else 0
        for (i in 0 until name.length) payload[2 + i] = name[i].code.toByte()
        return MbpPacket(sync, MbpProtocol.FILE_TRANSFER, MbpTransferType.OPEN, payload)
    }
}
