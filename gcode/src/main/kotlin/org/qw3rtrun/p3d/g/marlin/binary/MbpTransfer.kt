package org.qw3rtrun.p3d.g.marlin.binary

/**
 * Writes one file to the printer's card over the binary protocol, as a state machine and nothing
 * else: feed it a reply, it tells you the next bytes to transmit.
 *
 * **No I/O, no timers, no threads.** The reference Python and `kmbp` both tangle the protocol into
 * the transport - a serial port, a socket, sleeps, a background receive loop, a shared reply queue
 * that one coroutine wrote and another polled - and that is where their bugs lived. Here the
 * sequencing is a `when` over `(state, reply)` and the transport is the caller's, which is also what
 * makes every path below testable without a printer.
 *
 * The session, as the firmware runs it:
 *
 * ```
 * >> M28 B1 (ASCII)   << ok                                     binary mode is on
 * >> SYNC             << ss0,512,0.1.0                          the handshake; no ok for this one
 * >> QUERY            << ok0  PFT:version:0.1.0:compression:none
 * >> OPEN             << ok1  PFT:success                        dummy(0) comp(0) name NUL
 * >> WRITE block 0    << ok2                                    an ok is the only answer to a write
 * >> WRITE block n    << ok..
 * >> CLOSE            << ok   PFT:success
 * ```
 *
 * **An `ok` means block progress only while writing.** Every packet is acknowledged, and the
 * firmware writes that `ok` *before* it dispatches the packet, so `OPEN` is answered `ok` then
 * `PFT:success` in that order. Reading an `ok` as progress in any other state would run the block
 * counter ahead of the file.
 *
 * Compression is deliberately absent: there is no heatshrink encoder in this module, so the `OPEN`
 * payload's compression flag is always clear. What the client advertised is kept in
 * [clientCompression], [clientWindow] and [clientLookahead] for whoever ports one. That also
 * retires the reference's compression arithmetic, which divided by the *uncompressed* length -
 * always a ratio of exactly 1.0, and a division by zero on an empty file.
 *
 * The file is held as one array because this module does no I/O and so cannot read it in pieces;
 * blocks are sliced out of it by index at the moment of transmission rather than copied up front.
 * A caller streaming something too large to hold wants a second entry point that takes a block at a
 * time, and the sequencing here is already written to allow one.
 *
 * ```
 * val window = MbpSendWindow()
 * window.on(MbpStreamSync(0, 512, "0.1.0"))
 * val transfer = MbpTransfer(window, "test.gco", file)
 * var step = transfer.start()                       // MbpTransmit, the QUERY packet
 * step = transfer.on(MbpOk(0))                      // MbpIdle - the packet is in, await the PFT
 * step = transfer.on(MbpTransferVersion("0.1.0", "none", 0, 0))   // MbpTransmit, the OPEN packet
 * ```
 *
 * @property name the file to create on the card, as the firmware will see it
 * @property dummy send every byte but write none - the link benchmark the reference calls `--test`
 * @property blockSize the payload size of one write, read from [MbpSendWindow.blockSize] at
 *   construction, so build the transfer after the handshake
 * @property blocks how many write packets the file needs; 0 for an empty file
 * @property blocksWritten how many of them the firmware has acknowledged
 * @property state where the state machine is
 * @property reason why it gave up, or empty while it has not
 * @property clientCompression the algorithm the client said it can decompress, once `QUERY` answered
 * @property clientWindow the client's heatshrink window size in bits, or 0
 * @property clientLookahead the client's heatshrink lookahead size in bits, or 0
 */
class MbpTransfer(
    private val window: MbpSendWindow,
    val name: String,
    private val data: ByteArray,
    val dummy: Boolean = false,
) {

    init {
        require(name.isNotEmpty()) { "a binary transfer needs a filename" }
        require(window.blockSize >= 1) { "block size must be positive, was ${window.blockSize}" }
        var i = 0
        while (i < name.length) {
            val c = name[i]
            require(c >= ' ' && c <= '~') { "filename is not printable ASCII: $name" }
            i++
        }
    }

    val blockSize: Int = window.blockSize

    val blocks: Int = (data.size + blockSize - 1) / blockSize

    var blocksWritten: Int = 0
        private set

    var state: MbpTransferState = MbpTransferState.IDLE
        private set

    var reason: String = ""
        private set

    var clientCompression: String = ""
        private set

    var clientWindow: Int = 0
        private set

    var clientLookahead: Int = 0
        private set

    private var retriedOpen = false

    /** How many payload bytes the firmware has acknowledged. */
    val bytesWritten: Int
        get() = if (blocksWritten >= blocks) data.size else blocksWritten * blockSize

    /**
     * Progress as whole percent, reaching exactly 100 when the last block is acknowledged and not
     * before - the reference reported `i / blocks` and so never printed 100, then printed a second
     * hard-coded 100% line to cover it up. Integer arithmetic, so there is no locale and no
     * formatting decision to get wrong.
     *
     * ```
     * transfer.percent()   // 0, then 50, then 100 for a two-block file
     * ```
     *
     * @return `0..100`; 100 for an empty file, which has nothing left to send
     */
    fun percent(): Int = if (blocks == 0) 100 else blocksWritten * 100 / blocks

    /**
     * Begins the transfer: the `QUERY` packet, which asks the client for its version and whether it
     * can decompress.
     *
     * ```
     * MbpTransfer(window, "a.gco", data).start()   // MbpTransmit(AD B5 .. 10 00 00 ..)
     * ```
     *
     * @return the bytes to transmit, or [MbpGaveUp] when it has already been started or the window
     *   already has a packet in flight
     */
    fun start(): MbpStep {
        if (state != MbpTransferState.IDLE) return step("already started")
        state = MbpTransferState.QUERY
        return transmit(MbpTransferType.QUERY, MbpPacket.EMPTY)
    }

    /**
     * Feeds one reply in and says what to transmit next.
     *
     * The stream replies go to [MbpSendWindow] first, because a resend or a fatal error outranks
     * whatever the file transfer was doing; what the window declines is a `PFT:` line and is read
     * here. The two tables:
     *
     * ```
     * MbpResendNeeded             transmit those bytes again
     * MbpFatal, MbpOutOfSync      give up; the stream has to be resynchronised
     * MbpAccepted while WRITING   one block is on the card; transmit the next, or CLOSE
     * MbpAccepted otherwise       the packet is in; wait for its PFT: line
     *
     * PFT:version: while QUERY    record it, transmit OPEN
     * PFT:success  while OPENING  transmit the first block, or CLOSE for an empty file
     * PFT:success  while ABORTING transmit OPEN again - once
     * PFT:success  while CLOSING  done
     * PFT:success  while CANCELLING  gave up, with the reason abort() was given
     * PFT:busy     while OPENING  transmit ABORT, then retry the open
     * PFT:fail, PFT:ioerror, PFT:invalid, PTF:invalid    give up, with the reason
     * ```
     *
     * @param reply one reply read off the stream
     * @return the next step: bytes to transmit, nothing to do, finished, or gave up
     */
    fun on(reply: MbpRs<*>): MbpStep {
        if (state == MbpTransferState.DONE) return MbpFinished
        if (state == MbpTransferState.GAVE_UP) return MbpGaveUp(reason)

        val ack = window.on(reply)
        when (ack) {
            is MbpResendNeeded -> return MbpTransmit(ack.bytes)
            is MbpFatal -> return step("fatal error on packet ${ack.sync}")
            is MbpOutOfSync -> return step("out of sync: waiting on ${ack.expected}, got ${ack.received}")
            is MbpAccepted -> {
                if (state != MbpTransferState.WRITING) return MbpIdle
                blocksWritten++
                if (blocksWritten < blocks) return transmit(MbpTransferType.WRITE, data, blocksWritten)
                state = MbpTransferState.CLOSING
                return transmit(MbpTransferType.CLOSE, MbpPacket.EMPTY)
            }

            else -> {}
        }

        when (reply) {
            is MbpTransferVersion -> {
                if (state != MbpTransferState.QUERY) return MbpIdle
                clientCompression = reply.compression
                clientWindow = reply.window
                clientLookahead = reply.lookahead
                state = MbpTransferState.OPENING
                return transmit(MbpTransferType.OPEN, openPayload())
            }

            is MbpTransferSuccess -> return when (state) {
                MbpTransferState.OPENING -> {
                    if (blocks == 0) {
                        state = MbpTransferState.CLOSING
                        transmit(MbpTransferType.CLOSE, MbpPacket.EMPTY)
                    } else {
                        state = MbpTransferState.WRITING
                        transmit(MbpTransferType.WRITE, data, 0)
                    }
                }

                MbpTransferState.ABORTING -> {
                    state = MbpTransferState.OPENING
                    transmit(MbpTransferType.OPEN, openPayload())
                }

                MbpTransferState.CLOSING -> {
                    state = MbpTransferState.DONE
                    MbpFinished
                }

                MbpTransferState.CANCELLING -> {
                    state = MbpTransferState.GAVE_UP
                    MbpGaveUp(reason)
                }

                else -> MbpIdle
            }

            is MbpTransferBusy -> {
                if (state != MbpTransferState.OPENING) return MbpIdle
                if (retriedOpen) return step("the client stayed busy after an abort")
                retriedOpen = true
                state = MbpTransferState.ABORTING
                return transmit(MbpTransferType.ABORT, MbpPacket.EMPTY)
            }

            is MbpTransferFail -> return step("the client cannot open $name")
            is MbpTransferIoError -> return step("the client's storage reported an IO error")
            is MbpTransferInvalid -> return step("the client has no open file")
            is MbpUnknownPacketType -> return step("the client does not know this packet type")
            else -> return MbpIdle
        }
    }

    /**
     * Gives up and asks the client to throw away whatever it has - the `ABORT` packet. For a caller
     * that has run out of retries or whose user cancelled.
     *
     * Enters [MbpTransferState.CANCELLING], which is a different state from the
     * [MbpTransferState.ABORTING] a `PFT:busy` uses: both send the same packet and both are
     * answered `PFT:success`, but one goes on to reopen the file and this one is finished. Sharing
     * one state would make a cancelled transfer restart itself.
     *
     * Unconditional: a block is in flight for almost all of a transfer, so the outstanding packet
     * is abandoned rather than waited for. It keeps its sync id, which the `ABORT` then reuses -
     * see `MbpSendWindow.abandon`.
     *
     * ```
     * transfer.abort("the operator cancelled")   // MbpTransmit, the ABORT packet
     * transfer.on(MbpOk(4))                      // MbpIdle
     * transfer.on(MbpTransferSuccess)            // MbpGaveUp("the operator cancelled")
     * ```
     *
     * @param why what to record in [reason]
     * @return the bytes to transmit
     */
    fun abort(why: String): MbpStep {
        reason = why
        window.abandon()
        val bytes = window.send(MbpProtocol.FILE_TRANSFER, MbpTransferType.ABORT, MbpPacket.EMPTY)
        if (bytes == null) return step(why)
        state = MbpTransferState.CANCELLING
        return MbpTransmit(bytes)
    }

    private fun openPayload(): ByteArray {
        val payload = ByteArray(2 + name.length + 1)
        payload[0] = if (dummy) 1 else 0
        payload[1] = 0
        var i = 0
        while (i < name.length) {
            payload[2 + i] = name[i].code.toByte()
            i++
        }
        return payload
    }

    private fun transmit(type: Int, payload: ByteArray, block: Int = -1): MbpStep {
        val from = if (block < 0) 0 else block * blockSize
        var to = if (block < 0) payload.size else from + blockSize
        if (to > payload.size) to = payload.size
        val bytes = window.send(MbpProtocol.FILE_TRANSFER, type, payload, from, to)
        if (bytes == null) return step("a packet is already in flight")
        return MbpTransmit(bytes)
    }

    private fun step(why: String): MbpStep {
        state = MbpTransferState.GAVE_UP
        reason = why
        return MbpGaveUp(why)
    }
}

/**
 * Where an [MbpTransfer] is. One state per packet it is waiting on, plus the two it ends in.
 *
 * ```
 * IDLE -> QUERY -> OPENING -> WRITING -> CLOSING -> DONE
 *                    |  ^
 *       (PFT:busy)   +--+ ABORTING          and CANCELLING -> GAVE_UP from anywhere
 * ```
 */
enum class MbpTransferState {

    /** Constructed, nothing sent. */
    IDLE,

    /** A `QUERY` is in flight; waiting for `PFT:version:`. */
    QUERY,

    /** An `OPEN` is in flight; waiting for `PFT:success`, `PFT:busy` or `PFT:fail`. */
    OPENING,

    /** Blocks are going out; each `ok` is one block on the card. */
    WRITING,

    /** A `CLOSE` is in flight; waiting for `PFT:success`. */
    CLOSING,

    /** An `ABORT` is in flight to clear a busy client; the `OPEN` will be retried after it. */
    ABORTING,

    /** An `ABORT` is in flight because the caller cancelled; nothing follows it. */
    CANCELLING,

    /** The file is closed on the card. */
    DONE,

    /** Something unrecoverable happened; `MbpTransfer.reason` says what. */
    GAVE_UP,
}

/**
 * What an [MbpTransfer] wants next. The caller transmits [MbpTransmit.bytes] when it gets one,
 * waits for the next reply on [MbpIdle], and stops on either terminal step.
 *
 * ```
 * var step = transfer.start()
 * while (step is MbpTransmit) { link.write(step.bytes); step = transfer.on(link.read()) }
 * ```
 */
sealed interface MbpStep

/**
 * Transmit these bytes, then wait for a reply.
 *
 * @property bytes one whole framed packet
 */
class MbpTransmit(val bytes: ByteArray) : MbpStep

/** Nothing to send; the reply that just arrived was expected but not the one being waited for. */
object MbpIdle : MbpStep

/** The file is written and closed. */
object MbpFinished : MbpStep

/**
 * The transfer stopped and will not continue.
 *
 * @property reason what went wrong, in the words `MbpTransfer.reason` keeps
 */
data class MbpGaveUp(val reason: String) : MbpStep
