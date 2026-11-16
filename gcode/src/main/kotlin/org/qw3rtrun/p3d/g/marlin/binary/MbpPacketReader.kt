package org.qw3rtrun.p3d.g.marlin.binary

/**
 * Reads Marlin Binary Protocol packets out of a byte stream: one byte in at a time, one [MbpFrame]
 * out whenever a packet finishes, broken or whole.
 *
 * **The mirror image of `BinaryStream::receive` in the firmware, as a state machine over
 * `(state, byte)`**, and written that way deliberately - a decoder that took a whole packet as an
 * array could not do the two things this protocol needs. It could not resynchronise: the token is
 * hunted for with a sliding two-byte window, so any amount of line noise, a half packet or an ASCII
 * reply in the middle of the stream is skipped rather than fatal. And it could not be fed by a
 * transport, which gets whatever bytes a read returns and never a packet boundary.
 *
 * This is the receiving half, so **nothing here throws on bad input**. A corrupt header, a corrupt
 * payload and an over-wide payload each come back as an [MbpFrame] carrying what was read; the
 * reader then returns to hunting for a token, exactly as the firmware's `PACKET_RESET` does. The
 * one consequence worth knowing is that the tail of a rejected packet is itself scanned for a
 * token, so a payload containing `AD B5` can produce one further bogus frame before the stream
 * settles - the firmware has the same property and answers it with a resend request.
 *
 * A host mostly does not need this: in binary mode the firmware answers in *ASCII*
 * ([MbpRsDecoder]), and packets travel host to printer only. It is here because it is the one way
 * to test the encoder against something other than itself, and because a simulator or a proxy on
 * this side of the link has to read what a host wrote.
 *
 * Not thread-safe, and holds one payload buffer for its lifetime, so one reader per stream.
 *
 * ```
 * val reader = MbpPacketReader()
 * reader.frames(MbpCodec.encode(MbpPacket(7, 1, 3, byteArrayOf(1, 2, 3))))
 * // [MbpPacket(sync=7, protocol=1, type=3, payload=3 bytes)]
 * ```
 *
 * @property limit the widest payload this reader accepts; the firmware's own receive buffer is 512
 */
class MbpPacketReader(val limit: Int = 512) {

    private enum class State { TOKEN, HEADER, DATA, FOOTER }

    private var state = State.TOKEN
    private var previous = -1
    private var got = 0
    private var sync = 0
    private var meta = 0
    private var size = 0
    private var declared = 0
    private var headerSum = 0
    private val sum = Fletcher16()
    private val buffer = ByteArray(limit)

    /**
     * Folds one byte in and answers the frame it completed, or null while a frame is still
     * incomplete - which is most bytes.
     *
     * ```
     * val reader = MbpPacketReader()
     * reader.read(0xAD)   // null
     * reader.read(0xB5)   // null - the token is complete, the header is not
     * ```
     *
     * @param byte the next byte of the stream; only its low eight bits are read
     * @return the packet or the failure this byte completed, or null if it completed neither
     */
    fun read(byte: Int): MbpFrame? {
        val value = byte and 0xff
        when (state) {
            State.TOKEN -> {
                if (previous == (MbpCodec.TOKEN and 0xff) && value == (MbpCodec.TOKEN shr 8)) {
                    state = State.HEADER
                    previous = -1
                    got = 0
                    sum.reset()
                } else {
                    previous = value
                }
                return null
            }

            State.HEADER -> {
                when (got) {
                    0 -> sync = value
                    1 -> meta = value
                    2 -> size = value
                    3 -> size = size or (value shl 8)
                    4 -> declared = value
                    else -> declared = declared or (value shl 8)
                }
                sum.add(value)
                got++
                if (got == 4) headerSum = sum.get()
                if (got < 6) return null

                state = State.TOKEN
                if (declared != headerSum) return MbpHeaderCorrupt(sync, declared, headerSum)
                if (size > limit) return MbpPayloadTooLarge(sync, size, limit)
                if (size == 0) return MbpPacket(sync, meta shr 4, meta and 0xf)

                state = State.DATA
                got = 0
                return null
            }

            State.DATA -> {
                buffer[got] = value.toByte()
                sum.add(value)
                got++
                if (got < size) return null
                state = State.FOOTER
                got = 0
                declared = 0
                return null
            }

            State.FOOTER -> {
                declared = if (got == 0) value else declared or (value shl 8)
                got++
                if (got < 2) return null

                state = State.TOKEN
                val full = sum.get()
                if (declared != full) {
                    return MbpPayloadCorrupt(sync, meta shr 4, meta and 0xf, declared, full)
                }
                return MbpPacket(sync, meta shr 4, meta and 0xf, buffer.copyOf(size))
            }
        }
    }

    /**
     * Every frame a whole buffer completes - the form a test or a file-backed reader wants. State
     * carries across calls, so a packet split over two buffers is read by feeding both in turn.
     *
     * ```
     * val reader = MbpPacketReader()
     * reader.frames(firstHalf) + reader.frames(secondHalf)   // one packet, read across the split
     * ```
     *
     * @param bytes the buffer to read
     * @param from the first index to read
     * @param to one past the last index to read
     * @return the frames completed by those bytes, in order; empty when none finished
     */
    fun frames(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): List<MbpFrame> {
        val out = ArrayList<MbpFrame>()
        var i = from
        while (i < to) {
            val frame = read(bytes[i].toInt())
            if (frame != null) out.add(frame)
            i++
        }
        return out
    }

    /** Throws away any half-read packet and goes back to hunting for a token. */
    fun reset() {
        state = State.TOKEN
        previous = -1
        got = 0
        sum.reset()
    }
}
