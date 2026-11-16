package org.qw3rtrun.p3d.g.marlin.binary

/**
 * One packet of the Marlin Binary Protocol Mark II, in both directions: what [MbpCodec] lays out as
 * bytes and what [MbpPacketReader] hands back when it has read a whole one off the wire.
 *
 * The wire layout, from `Marlin/src/feature/binary_stream.h` (bugfix-2.1.x) and matching the
 * reference Python `transfer.py`:
 *
 * ```
 * AD B5   token, little-endian 0xB5AD, not covered by either checksum
 * ss      sync id, 0..255, the only thing a reply is correlated by
 * pt      protocol in the high nibble, packet type in the low nibble
 * zz zz   payload size, little-endian, 16 bits
 * cc cc   checksum of the four bytes above, little-endian
 * ...     payload, `size` bytes - absent, with no footer at all, when size is 0
 * ff ff   checksum of everything from the sync id to the last payload byte, the header
 *         checksum field included
 * ```
 *
 * [protocol] and [type] are plain `Int` nibbles rather than enums because the reader must carry
 * back whatever arrived - an unknown protocol is a thing the firmware itself answers with
 * `Unsupported Binary Protocol` rather than a thing that cannot be received. [MbpProtocol],
 * [MbpControlType] and [MbpTransferType] name the values that mean something.
 *
 * Not a `data class`: the payload is a `ByteArray`, and a generated `equals` would compare it by
 * identity, so two reads of the same bytes would be unequal. [equals] compares the payload's
 * contents, which is what every test and every correlation check in this package wants.
 *
 * ```
 * MbpCodec.encode(MbpPacket(0, MbpProtocol.CONTROL, MbpControlType.SYNC))   // AD B5 00 01 00 00 01 03
 * ```
 *
 * @property sync the sync id this packet carries, `0..255`
 * @property protocol the protocol nibble, `0..15`
 * @property type the packet-type nibble, `0..15`
 * @property payload the packet's data; empty for a packet that carries none
 */
class MbpPacket(
    val sync: Int,
    val protocol: Int,
    val type: Int,
    val payload: ByteArray = EMPTY,
) : MbpFrame {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MbpPacket) return false
        return sync == other.sync && protocol == other.protocol && type == other.type &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sync
        result = 31 * result + protocol
        result = 31 * result + type
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "MbpPacket(sync=$sync, protocol=$protocol, type=$type, payload=${payload.size} bytes)"

    companion object {

        /** The payload of a packet that carries none; shared because it has nothing to mutate. */
        val EMPTY = ByteArray(0)
    }
}

/**
 * The protocol nibble: which of the firmware's two protocols a packet belongs to.
 * `BinaryStream::Protocol` in `binary_stream.h`.
 *
 * ```
 * MbpPacket(0, MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY)
 * ```
 */
object MbpProtocol {

    /** The stream's own control protocol: synchronise, and leave binary mode. */
    const val CONTROL = 0

    /** The SD file-transfer protocol - everything [MbpTransfer] sends. */
    const val FILE_TRANSFER = 1
}

/**
 * The packet types of [MbpProtocol.CONTROL]. `BinaryStream::ProtocolControl`, which numbers from
 * one rather than zero.
 *
 * **[SYNC] is the one packet the firmware answers without an `ok`.** It is handled while the header
 * is still being read, before the sync id is even checked, so it is both the only packet that works
 * when host and firmware disagree about the sync id and the only one whose reply is an `ss` alone.
 * [MbpSendWindow] clears its outstanding packet on that `ss` for exactly this reason.
 */
object MbpControlType {

    /** Ask for the stream's sync id, buffer size and protocol version. Answered `ss<n>,<size>,<v>`. */
    const val SYNC = 1

    /** Leave binary mode and go back to reading ASCII G-code. Answered with a plain `ok<n>`. */
    const val CLOSE = 2
}

/**
 * The packet types of [MbpProtocol.FILE_TRANSFER]. `SDFileTransferProtocol::FileTransfer`.
 *
 * Every one of them is answered with an `ok<n>` for the packet itself, written *before* the
 * firmware dispatches it, and then with a `PFT:` line for what the dispatch did - except [WRITE],
 * which says nothing further when it succeeds. That is why [MbpTransfer] treats an `ok` as block
 * progress only while it is writing.
 */
object MbpTransferType {

    /** Ask for the file-transfer version and whether the client can decompress. */
    const val QUERY = 0

    /** Open a file for writing: `dummy(1) | compression(1) | name | NUL`. */
    const val OPEN = 1

    /** Close the open file. */
    const val CLOSE = 2

    /** Append a block to the open file. */
    const val WRITE = 3

    /** Throw away the open file and stop the transfer. */
    const val ABORT = 4
}

/**
 * What [MbpPacketReader] completed: a whole [MbpPacket], or one of the three ways a packet can
 * arrive broken.
 *
 * **The broken ones are values and not exceptions.** This is a byte stream off a serial link, so a
 * corrupt packet is the normal case the protocol exists to handle - the firmware answers one with
 * `rs` and carries on, and so must a host. Each variant carries what was read, so a caller can log
 * it, count it, and ask for a resend.
 *
 * ```
 * MbpPacketReader().frames(bytes)   // [MbpPacket(...), MbpHeaderCorrupt(7, 4660, 4661)]
 * ```
 */
sealed interface MbpFrame

/**
 * A header whose checksum field disagrees with the four bytes it covers, so nothing in it can be
 * trusted - [sync] included, which is why the firmware logs it as `Packet header(<n>?) corrupt`
 * with the question mark. Reported anyway: it is the only clue to which packet was lost.
 *
 * @property sync the sync byte as read, which may itself be the corrupted one
 * @property declared the checksum the header carried
 * @property computed the checksum of the bytes that arrived
 */
data class MbpHeaderCorrupt(val sync: Int, val declared: Int, val computed: Int) : MbpFrame

/**
 * A header that checked out and a payload that did not - the footer checksum disagrees with the
 * bytes between it and the sync id. The header fields are trustworthy here, so all of them are
 * carried: a host can ask for this exact packet again.
 *
 * @property sync the packet's sync id
 * @property protocol the protocol nibble
 * @property type the packet-type nibble
 * @property declared the checksum the footer carried
 * @property computed the checksum of the bytes that arrived
 */
data class MbpPayloadCorrupt(
    val sync: Int,
    val protocol: Int,
    val type: Int,
    val declared: Int,
    val computed: Int,
) : MbpFrame

/**
 * A header that checked out and declared more payload than the reader will hold. The firmware's own
 * answer to the mirror case is `Datastream packet data buffer overrun` and a fatal error; a host
 * reading a reply stream only needs to know the packet is unreadable and resynchronise.
 *
 * @property sync the packet's sync id
 * @property size the payload size the header declared
 * @property limit the reader's own ceiling
 */
data class MbpPayloadTooLarge(val sync: Int, val size: Int, val limit: Int) : MbpFrame

/**
 * Lays a packet out as the bytes that go on the wire.
 *
 * The writing direction, so a bad argument throws: a payload too wide for the 16-bit size field, or
 * a range outside the array it names, is a mistake in the calling code and failing at the call site
 * is the point. Only the *reading* direction answers with values - see [MbpFrame].
 *
 * **The two checksums are one running sum, not two.** The firmware folds every header byte after
 * the token into a single accumulator, snapshots it after four bytes to get the header checksum,
 * and keeps going through the header checksum field itself and then the payload to get the footer.
 * So the footer covers the header checksum bytes too. Computing them as two independent sums gives
 * a different footer and every packet with a payload is rejected.
 *
 * ```
 * MbpCodec.encode(MbpPacket(0, 1, 0, byteArrayOf(0xAA.toByte(), 0xBB.toByte())))
 * // AD B5 00 10 02 00 12 34 AA BB BE 72
 * ```
 */
object MbpCodec {

    /** The packet start token, `0xB5AD`, written low byte first and covered by no checksum. */
    const val TOKEN = 0xB5AD

    /** Token, sync, protocol/type, size and the header checksum: eight bytes. */
    const val HEADER_SIZE = 8

    /** The footer checksum, present only when the payload is. */
    const val FOOTER_SIZE = 2

    /** The widest payload the 16-bit size field can describe. */
    const val MAX_PAYLOAD = 0xffff

    /**
     * The bytes of [packet].
     *
     * ```
     * MbpCodec.encode(MbpPacket(0, MbpProtocol.CONTROL, MbpControlType.SYNC))
     * // AD B5 00 01 00 00 01 03
     * ```
     *
     * @param packet the packet to lay out
     * @return a fresh array holding the whole packet
     * @throws IllegalArgumentException if the payload is wider than [MAX_PAYLOAD]
     */
    fun encode(packet: MbpPacket): ByteArray =
        encode(packet.sync, packet.protocol, packet.type, packet.payload, 0, packet.payload.size)

    /**
     * The same, for a payload that is a range of a larger buffer - how [MbpTransfer] emits a block
     * without copying the file first.
     *
     * ```
     * MbpCodec.encode(4, MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, file, 0, 512)
     * ```
     *
     * @param sync the sync id to stamp; only the low eight bits reach the wire
     * @param protocol the protocol nibble
     * @param type the packet-type nibble
     * @param payload the buffer holding the payload
     * @param from the first payload index
     * @param to one past the last payload index
     * @return a fresh array holding the whole packet
     * @throws IllegalArgumentException if the range is not inside [payload], or is wider than
     *   [MAX_PAYLOAD]
     */
    fun encode(sync: Int, protocol: Int, type: Int, payload: ByteArray, from: Int, to: Int): ByteArray {
        require(from >= 0 && to >= from && to <= payload.size) {
            "payload range $from..$to is not inside an array of ${payload.size}"
        }
        val size = to - from
        require(size <= MAX_PAYLOAD) { "payload of $size bytes exceeds the 16-bit size field" }

        val out = ByteArray(HEADER_SIZE + size + if (size == 0) 0 else FOOTER_SIZE)
        out[0] = (TOKEN and 0xff).toByte()
        out[1] = (TOKEN shr 8).toByte()
        out[2] = sync.toByte()
        out[3] = (((protocol and 0xf) shl 4) or (type and 0xf)).toByte()
        out[4] = size.toByte()
        out[5] = (size shr 8).toByte()

        val cs = Fletcher16()
        cs.add(out, 2, 6)
        val header = cs.get()
        out[6] = (header and 0xff).toByte()
        out[7] = (header shr 8).toByte()
        if (size == 0) return out

        cs.add(out, 6, 8)
        var i = 0
        while (i < size) {
            val b = payload[from + i]
            out[HEADER_SIZE + i] = b
            cs.add(b.toInt())
            i++
        }
        val full = cs.get()
        out[HEADER_SIZE + size] = (full and 0xff).toByte()
        out[HEADER_SIZE + size + 1] = (full shr 8).toByte()
        return out
    }
}
