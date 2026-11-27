package org.qw3rtrun.p3d.g.marlin.binary

import org.qw3rtrun.p3d.g.code.core.command.GCommand
import org.qw3rtrun.p3d.g.code.dsl.B
import org.qw3rtrun.p3d.g.code.dsl.M

/**
 * The host's side of the Marlin Binary Protocol stream: stamp one packet with the next sync id,
 * hold its exact bytes until the firmware acknowledges that id, and say what to do when it does not.
 *
 * **One packet outstanding at a time, correlated by sync id** - the protocol gives the host nothing
 * else to correlate by, and the window is one deep because the firmware reads one packet at a time
 * into one buffer. That makes this the binary counterpart of `GSendWindow`, with the same division
 * of labour: no I/O here, the caller transmits what a method returns, and feeds replies back in.
 *
 * **The correlation is the whole point of the class.** The Python reference and `kmbp` both tracked
 * the stream with one boolean and a shared FIFO of replies, so *any* reply satisfied whichever
 * request happened to be waiting. Their handshake - an ASCII `M28 B1` answered with a bare `ok`,
 * then a `SYNC` packet answered with `ss` - therefore depended on the two arriving in that order,
 * and wedged in a retransmit loop until timeout when they did not. Here a bare `ok` is not an
 * [MbpRs] at all, an `ok<n>` for an id we are not waiting on reports [MbpOutOfSync] rather than
 * completing something, and the order the two replies arrive in cannot matter.
 *
 * Nothing in the reading direction throws: a desynchronised stream and a fatal error are both
 * [MbpAck] values, because both are things a serial link does and a host has to recover from.
 *
 * Not thread-safe, no atomics, one window per stream - same contract as `GSendWindow`, and for the
 * same reason: it has to port to C.
 *
 * ```
 * val window = MbpSendWindow()
 * window.resync()                                    // the SYNC packet's bytes; send them
 * window.on(MbpStreamSync(0, 512, "0.1.0"))          // MbpSynced(0, 512, "0.1.0")
 * window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY, MbpPacket.EMPTY)
 * window.on(MbpOk(0))                                // MbpAccepted - the window is free again
 * ```
 *
 * @property next the sync id the next packet will carry
 * @property blockSize the largest payload this window will send, never above [buffer]
 * @property buffer the firmware's payload buffer as `ss` reported it, or 0 before the handshake
 * @property version the protocol version `ss` reported, or empty before the handshake
 * @property synchronized whether a handshake has completed and not been invalidated since
 * @property errors how many retransmits and fatal errors this stream has seen
 */
class MbpSendWindow(blockSize: Int = 512) {

    var next: Int = 0
        private set

    var blockSize: Int = blockSize
        private set

    var buffer: Int = 0
        private set

    var version: String = ""
        private set

    var synchronized: Boolean = false
        private set

    var errors: Int = 0
        private set

    private var pending: ByteArray? = null

    private var pendingSync = -1

    private var pendingIsSync = false

    /** The id of the packet last accepted, or -1 since a handshake - what a duplicate `ok` names. */
    private var accepted = -1

    /** Whether a packet is sent and not yet acknowledged, so no other may be stamped. */
    val inFlight: Boolean
        get() = pending != null

    /**
     * The `SYNC` control packet: ask the firmware where the stream is. The way in, and the only way
     * back from a desynchronised or fatally errored stream.
     *
     * Held as outstanding like any other packet, but it is cleared by an `ss` rather than an `ok` -
     * the firmware answers `SYNC` while it is still reading the header, before it checks the sync
     * id, and never sends an `ok` for it. A window that waited for one would retransmit forever,
     * which is what the Python reference does if the `ss` is consumed by something else.
     *
     * ```
     * MbpSendWindow().resync()   // AD B5 00 01 00 00 01 03
     * ```
     *
     * @return the packet's bytes to transmit, or null when one is already outstanding
     */
    fun resync(): ByteArray? = send(MbpProtocol.CONTROL, MbpControlType.SYNC, MbpPacket.EMPTY)

    /**
     * The `CLOSE` control packet: leave binary mode and go back to reading ASCII G-code. Answered
     * with a plain `ok<n>`, because unlike `SYNC` it is dispatched through the normal path.
     *
     * @return the packet's bytes to transmit, or null when one is already outstanding
     */
    fun close(): ByteArray? = send(MbpProtocol.CONTROL, MbpControlType.CLOSE, MbpPacket.EMPTY)

    /**
     * Stamps [payload] with the next sync id, frames it, and holds the bytes until they are
     * acknowledged.
     *
     * Returns null when a packet is already outstanding - the caller's signal to wait for a reply
     * rather than an error, and the same shape `GSendWindow.send` uses when its window is full. A
     * refused send consumes no sync id.
     *
     * ```
     * val window = MbpSendWindow()
     * window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, block)   // bytes
     * window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, block)   // null, one is in flight
     * ```
     *
     * @param protocol the protocol nibble, [MbpProtocol]
     * @param type the packet-type nibble, [MbpControlType] or [MbpTransferType]
     * @param payload the buffer holding the payload
     * @param from the first payload index
     * @param to one past the last payload index
     * @return the bytes to transmit, or null when a packet is already outstanding
     * @throws IllegalArgumentException if the payload is wider than [blockSize]
     */
    fun send(
        protocol: Int,
        type: Int,
        payload: ByteArray,
        from: Int = 0,
        to: Int = payload.size,
    ): ByteArray? {
        if (pending != null) return null
        require(to - from <= blockSize) {
            "payload of ${to - from} bytes exceeds the negotiated block size of $blockSize"
        }

        val bytes = MbpCodec.encode(next, protocol, type, payload, from, to)
        pending = bytes
        pendingSync = next
        pendingIsSync = protocol == MbpProtocol.CONTROL && type == MbpControlType.SYNC
        return bytes
    }

    /**
     * The outstanding packet's bytes again, for a caller whose reply timer expired. Counts an error,
     * because that is what a lost packet is.
     *
     * The firmware tolerates the duplicate: a packet whose sync id is one behind what it expects is
     * re-acknowledged and its payload dropped, which is how a lost `ok` resolves.
     *
     * @return the bytes to transmit again, or null when nothing is outstanding
     */
    fun retransmit(): ByteArray? {
        val bytes = pending ?: return null
        errors++
        return bytes
    }

    /**
     * Throws the outstanding packet away without advancing the sync id - for a caller that has
     * given up on it and wants to send something else instead, which in practice means an `ABORT`.
     *
     * Not advancing is the point: the firmware is still waiting for that id, so the replacement
     * packet carries it and the stream stays synchronised. Advancing would leave a gap and the next
     * packet would be answered `rs`.
     *
     * ```
     * window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE, block)   // sync 4
     * window.abandon()                                                       // true
     * window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.ABORT, MbpPacket.EMPTY)   // sync 4
     * ```
     *
     * @return true when there was a packet to throw away
     */
    fun abandon(): Boolean {
        if (pending == null) return false
        clear()
        return true
    }

    /**
     * Feeds one reply in and says what it did to the stream.
     *
     * The whole state machine, as a `when` over the reply type:
     *
     * ```
     * ok<n>, n == outstanding   MbpAccepted       the window is free, next advances
     * ok<n>, n == last accepted MbpNotOurs        the duplicate a retransmit earns; see below
     * ok<n>, n != outstanding   MbpOutOfSync      a reply for a packet we are not waiting on
     * ok<n>, SYNC outstanding   MbpOutOfSync      SYNC is answered by ss alone; see below
     * rs<n>, n == outstanding   MbpResendNeeded   transmit those bytes again
     * rs<n>, n != outstanding   MbpOutOfSync      the firmware is somewhere else; resync
     * ss<n>,<buf>,<v>           MbpSynced         adopt all three, the window is free
     * fe<n>                     MbpFatal          synchronized is false until resync
     * anything else             MbpNotOurs        a PFT: line, for MbpTransfer to read
     * ```
     *
     * An `ok` never completes an outstanding `SYNC`, even when the ids match: the firmware does not
     * write one for that packet, so the only `ok` that can arrive while a `SYNC` is in flight is a
     * late one for an earlier packet that reused the id. Accepting it would advance [next] past the
     * firmware and desynchronise the stream from the very packet sent to synchronise it.
     *
     * A retransmit whose original was late rather than lost is answered twice: the firmware
     * re-acknowledges a packet whose id is one behind what it expects (see [retransmit]), and that
     * second `ok` can arrive after the next packet is already out. It names exactly the id this
     * window accepted last, so it is set aside rather than read as divergence - measured 2026-11-26
     * behind a 3s link stall, where reading it as out of sync ended the transfer. Only that one id:
     * anything older is still [MbpOutOfSync], and a handshake forgets it.
     *
     * [MbpOutOfSync] replaces the `SynchronizationError` the Python reference throws and the
     * forever-retransmit `kmbp` does: it is a value, the caller resynchronises, and a stray reply
     * from a previous session cannot complete a current request.
     *
     * ```
     * val window = MbpSendWindow()
     * window.resync()
     * window.on(MbpOk(0))                        // MbpOutOfSync - SYNC is answered by ss, not ok
     * window.on(MbpStreamSync(4, 512, "0.1.0"))  // MbpSynced(4, 512, "0.1.0"), next == 4
     * ```
     *
     * @param reply one reply read off the stream
     * @return what it did; see the table
     */
    fun on(reply: MbpRs<*>): MbpAck {
        when (reply) {
            is MbpOk -> {
                if (pending == null || pendingIsSync || reply.sync != pendingSync) {
                    if (reply.sync == accepted && !pendingIsSync) return MbpNotOurs
                    return MbpOutOfSync(pendingSync, reply.sync)
                }
                clear()
                accepted = reply.sync
                next = (next + 1) and 0xff
                return MbpAccepted
            }

            is MbpResend -> {
                errors++
                val bytes = pending
                if (bytes == null || reply.sync != pendingSync) {
                    return MbpOutOfSync(pendingSync, reply.sync)
                }
                return MbpResendNeeded(bytes)
            }

            is MbpStreamSync -> {
                next = reply.sync
                buffer = reply.buffer
                version = reply.version
                if (reply.buffer >= 1 && reply.buffer < blockSize) blockSize = reply.buffer
                synchronized = true
                accepted = -1
                clear()
                return MbpSynced(reply.sync, reply.buffer, reply.version)
            }

            is MbpFatalError -> {
                errors++
                synchronized = false
                accepted = -1
                clear()
                return MbpFatal(reply.sync)
            }

            else -> return MbpNotOurs
        }
    }

    private fun clear() {
        pending = null
        pendingSync = -1
        pendingIsSync = false
    }

    companion object {

        /**
         * `M28 B1` - the ASCII command that switches the printer into binary mode, the one thing a
         * host sends as text before everything becomes packets. Answered with a bare `ok` and
         * `echo:Switching to Binary Protocol`.
         *
         * Spelled with a separate `B1` word rather than as `M28`'s filename, because the firmware
         * reads it from the start of the string argument: `M28 B1` and the reference's `M28B1` both
         * parse, but `M28 test.gco B1` does not - the flag has to come first, and anything after it
         * is ignored. That is also why this is not `StartSDWrite`, whose generated model reads
         * everything after `M28` as one bare filename: `B1` is not a filename and the two commands
         * mean opposite things.
         *
         * ```
         * GEncoder.encode(MbpSendWindow.enterBinaryMode())   // "M28 B1"
         * ```
         *
         * @return the command to encode and send as ASCII
         */
        fun enterBinaryMode(): GCommand = M(28, B(1))
    }
}

/**
 * What a reply did to an [MbpSendWindow] - the output half of its state machine.
 *
 * All six are values, including the two failures, because a desynchronised stream and a fatal error
 * are things a serial link does rather than things a program got wrong.
 *
 * ```
 * MbpSendWindow().on(MbpOk(3))   // MbpOutOfSync(expected = -1, received = 3)
 * ```
 */
sealed interface MbpAck

/** The outstanding packet was acknowledged. The window is free and the sync id has advanced. */
object MbpAccepted : MbpAck

/**
 * The handshake landed: the window adopted the firmware's sync id, buffer size and version, and is
 * free again.
 *
 * @property sync the sync id the stream is now at
 * @property buffer the firmware's payload buffer in bytes
 * @property version the protocol version the firmware reported
 */
data class MbpSynced(val sync: Int, val buffer: Int, val version: String) : MbpAck

/**
 * The firmware asked for the outstanding packet again. [bytes] are the exact bytes it was sent -
 * re-encoding would be the same here, but holding them is what makes it impossible for a resend to
 * differ from the original.
 *
 * @property bytes the packet to transmit again
 */
class MbpResendNeeded(val bytes: ByteArray) : MbpAck

/**
 * A reply that named a sync id this window is not waiting on. Either a leftover from a previous
 * session, or the two ends have genuinely diverged; a `SYNC` packet is the only way to find out
 * which.
 *
 * @property expected the outstanding packet's sync id, or -1 when nothing is outstanding
 * @property received the sync id the reply named
 */
data class MbpOutOfSync(val expected: Int, val received: Int) : MbpAck

/**
 * The firmware reset its stream state. Nothing it is sent will be read until the next successful
 * handshake, so [MbpSendWindow.synchronized] is false from here.
 *
 * @property sync the sync id of the packet that failed
 */
data class MbpFatal(val sync: Int) : MbpAck

/**
 * A reply the window does not act on - every `PFT:` line, which belongs to [MbpTransfer]. Not an
 * error: the two state machines read the same stream and each ignores the other's traffic.
 */
object MbpNotOurs : MbpAck
