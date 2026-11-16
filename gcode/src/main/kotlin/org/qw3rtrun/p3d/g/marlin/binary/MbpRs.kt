package org.qw3rtrun.p3d.g.marlin.binary

import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.digitsEnd
import org.qw3rtrun.p3d.g.protocol.equalsAscii
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * What the firmware says back while it is in binary mode. **The packets go one way; the replies come
 * back as ASCII lines**, which is why this family is a `GRs` like every other reply in the module
 * and not a packet type.
 *
 * Four of them drive the stream and are correlated by sync id - [MbpOk], [MbpResend],
 * [MbpStreamSync] and [MbpFatalError], written by `BinaryStream::receive`. The rest are the SD
 * file-transfer protocol answering a dispatched packet, written by
 * `SDFileTransferProtocol::process` and all prefixed `PFT:`.
 *
 * ```
 * ok7                                     MbpOk(7)              - packet 7 is in
 * rs7                                     MbpResend(7)          - send packet 7 again
 * ss0,512,0.1.0                           MbpStreamSync(0, 512, "0.1.0")
 * fe7                                     MbpFatalError(7)      - resynchronise from scratch
 * PFT:version:0.1.0:compression:none      MbpTransferVersion("0.1.0", "none", 0, 0)
 * PFT:success                             MbpTransferSuccess
 * PFT:fail                                MbpTransferFail       - cannot open that file
 * PFT:busy                                MbpTransferBusy       - a transfer is already open
 * PFT:ioerror                             MbpTransferIoError    - the card refused a write
 * PFT:invalid                             MbpTransferInvalid    - close or write with no open file
 * PTF:invalid                             MbpUnknownPacketType  - the firmware's own typo
 * ```
 *
 * **`PTF:invalid` is not a mistake in this file.** `binary_stream.h` writes `PFT:invalid` for a
 * close or a write with no file open, and `PTF:invalid` - letters transposed - on the `default`
 * branch for a file-transfer packet type it does not know. They are two different events and the
 * module carries both, which is the fix for a long-standing confusion in the Python reference and
 * in `kmbp`: the reference registers only `PTF:invalid` and then compares against `PFT:invalid`, so
 * its "no open file" branch is unreachable, while `kmbp` registered only `PFT:invalid` and lost the
 * unknown-type reply instead.
 *
 * **This family is deliberately *not* in `MarlinRsDecoder`.** `rs7` is already a valid base-protocol
 * `Resend:` line - measured, `MarlinRsDecoder.decode("rs7")` answers `ResendRs(lineNumber = 7)` -
 * and the two number different things: a G-code line versus a binary packet. Registering both would
 * give one line two claimants and break the registry's partition property, and a host cannot tell
 * them apart from the text alone: it has to know which mode it put the printer in. So a caller in
 * binary mode reads its replies with [MbpRsDecoder] and everything else with `MarlinRsDecoder`.
 *
 * ```
 * MbpRsDecoder.decode("ss0,512,0.1.0")   // MbpStreamSync(0, 512, "0.1.0")
 * MbpRsDecoder.decode("ok")              // null - a bare ok is the ASCII protocol's
 * ```
 */
interface MbpRs<D : MbpRs<D>> : GEventRs<D>

/**
 * `ok<sync>` - the firmware read packet [sync] whole and has dispatched it.
 *
 * **Not the same reply as a bare `ok`,** and the distinction is what makes the handshake reliable.
 * Switching the printer into binary mode is an ASCII `M28 B1`, acknowledged with a plain `ok`; the
 * `SYNC` packet that follows is answered `ss`. The Python reference and `kmbp` both fed the two into
 * one FIFO behind one boolean, so whichever arrived first satisfied whichever request was waiting -
 * and when they arrived in the other order the handshake wedged in a retransmit loop until it timed
 * out. Here a bare `ok` is `SimpleOkRs` and carries no sync, this one carries one, and
 * [MbpSendWindow] only completes a request whose id matches.
 *
 * ```
 * MbpOk.decode("ok7")   // MbpOk(7)
 * MbpOk.decode("ok")    // null
 * ```
 *
 * @property sync the sync id of the packet being acknowledged, `0..255`
 */
data class MbpOk(val sync: Int) : MbpRs<MbpOk> {

    override fun encode(): String = "ok$sync"

    companion object : GRsDecoder<MbpOk> {

        override fun decode(line: String): MbpOk? {
            val sync = syncAfter(line, "ok") ?: return null
            return MbpOk(sync)
        }
    }
}

/**
 * `rs<sync>` - the firmware wants packet [sync] again: either its header or its payload failed a
 * checksum, or the stream starved mid-packet and timed out.
 *
 * The id is the firmware's *own* current sync, not the id of whatever arrived, so a value that does
 * not match the packet in flight means host and firmware disagree about where the stream is and the
 * answer is a fresh `SYNC` packet rather than a retransmit. [MbpSendWindow] reports that case as
 * `MbpOutOfSync`; the Python reference retransmits regardless and loops.
 *
 * ```
 * MbpResend.decode("rs7")   // MbpResend(7)
 * ```
 *
 * @property sync the sync id the firmware is waiting for, `0..255`
 */
data class MbpResend(val sync: Int) : MbpRs<MbpResend> {

    override fun encode(): String = "rs$sync"

    companion object : GRsDecoder<MbpResend> {

        override fun decode(line: String): MbpResend? {
            val sync = syncAfter(line, "rs") ?: return null
            return MbpResend(sync)
        }
    }
}

/**
 * `ss<sync>,<buffer>,<version>` - the answer to a `SYNC` control packet, and the whole of the
 * handshake: where the stream is, how much payload the firmware can hold, and which version of the
 * protocol it speaks.
 *
 * **The one reply that arrives without an `ok` beside it.** The firmware answers `SYNC` while it is
 * still reading the header, before it checks the sync id at all, so this packet works even when the
 * two ends disagree - and it is the only way out of a desynchronised stream.
 *
 * [buffer] is the firmware's receive buffer, 512 bytes in a stock build, and is the ceiling on a
 * write block. [version] is kept as the text that arrived rather than three numbers, so the reply
 * round-trips whatever a future firmware writes there.
 *
 * ```
 * MbpStreamSync.decode("ss0,512,0.1.0")   // MbpStreamSync(0, 512, "0.1.0")
 * ```
 *
 * @property sync the sync id the firmware expects next, `0..255`
 * @property buffer the firmware's payload buffer in bytes
 * @property version the protocol version, as written
 */
data class MbpStreamSync(val sync: Int, val buffer: Int, val version: String) : MbpRs<MbpStreamSync> {

    override fun encode(): String = "ss$sync,$buffer,$version"

    companion object : GRsDecoder<MbpStreamSync> {

        /**
         * `ss`, then three comma-separated fields, or null when the line is not one of these.
         *
         * Spec 9 throughout: a sync id outside `0..255` or a buffer size wider than the protocol's
         * 16-bit payload field is not something a firmware writes, so the line does not decode
         * rather than carrying a value that could not have arrived.
         *
         * @param line one received line
         * @return the handshake reply, or null when [line] is some other kind
         */
        override fun decode(line: String): MbpStreamSync? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii("ss")) return null

            var at = text.blanksEnd(2)
            var end = text.digitsEnd(at)
            if (end == at || end >= text.length || text[end] != ',') return null
            val sync = text.substring(at, end).toIntOrNull() ?: return null
            if (sync > 0xff) return null

            at = end + 1
            end = text.digitsEnd(at)
            if (end == at || end >= text.length || text[end] != ',') return null
            val buffer = text.substring(at, end).toIntOrNull() ?: return null
            if (buffer > MbpCodec.MAX_PAYLOAD) return null

            val version = text.substring(end + 1)
            return if (version.isEmpty()) null else MbpStreamSync(sync, buffer, version)
        }
    }
}

/**
 * `fe<sync>` - the firmware gave up on packet [sync] and reset its whole stream state. Nothing the
 * host sends will be read until it synchronises again.
 *
 * Written from `PACKET_ERROR`, which a stock build reaches only when the payload overruns the
 * receive buffer: `max_retries` is zero, which the firmware reads as "retry forever", so a corrupt
 * packet alone never gets here.
 *
 * ```
 * MbpFatalError.decode("fe7")   // MbpFatalError(7)
 * ```
 *
 * @property sync the sync id of the packet that failed, `0..255`
 */
data class MbpFatalError(val sync: Int) : MbpRs<MbpFatalError> {

    override fun encode(): String = "fe$sync"

    companion object : GRsDecoder<MbpFatalError> {

        override fun decode(line: String): MbpFatalError? {
            val sync = syncAfter(line, "fe") ?: return null
            return MbpFatalError(sync)
        }
    }
}

/**
 * `PFT:version:<version>:compression:none` or `...:compression:<algorithm>,<window>,<lookahead>` -
 * the answer to a `QUERY` packet, and the first thing a transfer asks for.
 *
 * [compression] is the algorithm the *client* can decompress, which is `heatshrink` in a build with
 * `BINARY_STREAM_COMPRESSION` and `none` otherwise, with [window] and [lookahead] its two
 * parameters in bits. **This module never asks for it**: no heatshrink encoder is ported, so
 * [MbpTransfer] always opens with the compression flag clear and only reports what the client said.
 * Carrying the parameters anyway is what a future encoder needs, and it is cheaper than discovering
 * later that the reply was parsed and thrown away.
 *
 * ```
 * MbpTransferVersion.decode("PFT:version:0.1.0:compression:none")
 * // MbpTransferVersion("0.1.0", "none", 0, 0)
 * MbpTransferVersion.decode("PFT:version:0.1.0:compression:heatshrink,8,4")
 * // MbpTransferVersion("0.1.0", "heatshrink", 8, 4)
 * ```
 *
 * @property version the file-transfer protocol version, as written
 * @property compression the algorithm the client can decompress, or `none`
 * @property window the heatshrink window size in bits, or 0 when there is no compression
 * @property lookahead the heatshrink lookahead size in bits, or 0 when there is no compression
 */
data class MbpTransferVersion(
    val version: String,
    val compression: String,
    val window: Int,
    val lookahead: Int,
) : MbpRs<MbpTransferVersion> {

    override fun encode(): String =
        if (window == 0 && lookahead == 0) PREFIX + version + MARK + compression
        else PREFIX + version + MARK + compression + "," + window + "," + lookahead

    companion object : GRsDecoder<MbpTransferVersion> {

        private const val PREFIX = "PFT:version:"

        private const val MARK = ":compression:"

        /**
         * The version and the compression clause, or null when the line is not one of these.
         *
         * The version runs to the first `:compression:`, which is safe because a version is digits
         * and dots. The clause is either one word or three comma-separated fields, and the two
         * numbers are checked to be digits before conversion so that `heatshrink,8` and
         * `heatshrink,x,4` decline rather than decode to something that did not arrive. A clause of
         * `<algorithm>,0,0` declines too: zero window and zero lookahead is how [encode] spells "no
         * compression clause at all", and accepting it here would break the round trip.
         *
         * @param line one received line
         * @return the version reply, or null when [line] is some other kind
         */
        override fun decode(line: String): MbpTransferVersion? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(PREFIX)) return null

            var mark = -1
            var at = PREFIX.length
            while (at < text.length) {
                if (text.startsWithAscii(MARK, at)) {
                    mark = at
                    break
                }
                at++
            }
            if (mark <= PREFIX.length) return null

            val version = text.substring(PREFIX.length, mark)
            val rest = text.substring(mark + MARK.length)
            if (rest.isEmpty()) return null

            var first = -1
            var second = -1
            var i = 0
            while (i < rest.length) {
                if (rest[i] == ',') {
                    if (first < 0) first = i else second = i
                }
                i++
            }
            if (first < 0 && second < 0) return MbpTransferVersion(version, rest, 0, 0)
            if (first < 1 || second <= first + 1 || second == rest.length - 1) return null
            if (rest.digitsEnd(first + 1) != second) return null
            if (rest.digitsEnd(second + 1) != rest.length) return null

            val window = rest.substring(first + 1, second).toIntOrNull() ?: return null
            val lookahead = rest.substring(second + 1).toIntOrNull() ?: return null
            if (window == 0 && lookahead == 0) return null
            return MbpTransferVersion(version, rest.substring(0, first), window, lookahead)
        }
    }
}

/**
 * `PFT:success` - the `OPEN`, `CLOSE` or `ABORT` packet did what it said.
 *
 * Which of the three it answers is the host's to know from what it sent, because the line is the
 * same for all of them - the reason [MbpTransfer] is a state machine rather than a sequence of
 * waits.
 *
 * ```
 * MbpTransferSuccess.decode("PFT:success")   // MbpTransferSuccess
 * ```
 */
object MbpTransferSuccess : MbpRs<MbpTransferSuccess>, GRsDecoder<MbpTransferSuccess> {

    override fun encode(): String = "PFT:success"

    override fun decode(line: String): MbpTransferSuccess? =
        if (line.equalsAscii("PFT:success")) this else null
}

/**
 * `PFT:fail` - the client could not open the file: no card, a bad path, or the name was not
 * null-terminated. The transfer cannot continue.
 *
 * ```
 * MbpTransferFail.decode("PFT:fail")   // MbpTransferFail
 * ```
 */
object MbpTransferFail : MbpRs<MbpTransferFail>, GRsDecoder<MbpTransferFail> {

    override fun encode(): String = "PFT:fail"

    override fun decode(line: String): MbpTransferFail? =
        if (line.equalsAscii("PFT:fail")) this else null
}

/**
 * `PFT:busy` - an `OPEN` arrived while a transfer was already open, which is what an interrupted
 * transfer leaves behind.
 *
 * The recovery is `ABORT` then `OPEN` again, and the firmware will also time one out by itself after
 * ten seconds of silence. [MbpTransfer] does the abort-and-retry once rather than looping, so a
 * client stuck busy fails with a reason instead of hanging.
 *
 * ```
 * MbpTransferBusy.decode("PFT:busy")   // MbpTransferBusy
 * ```
 */
object MbpTransferBusy : MbpRs<MbpTransferBusy>, GRsDecoder<MbpTransferBusy> {

    override fun encode(): String = "PFT:busy"

    override fun decode(line: String): MbpTransferBusy? =
        if (line.equalsAscii("PFT:busy")) this else null
}

/**
 * `PFT:ioerror` - the card refused a write, or refused to close the file. The bytes already sent are
 * on the card and the file is not valid.
 *
 * ```
 * MbpTransferIoError.decode("PFT:ioerror")   // MbpTransferIoError
 * ```
 */
object MbpTransferIoError : MbpRs<MbpTransferIoError>, GRsDecoder<MbpTransferIoError> {

    override fun encode(): String = "PFT:ioerror"

    override fun decode(line: String): MbpTransferIoError? =
        if (line.equalsAscii("PFT:ioerror")) this else null
}

/**
 * `PFT:invalid` - a `CLOSE` or a `WRITE` arrived with no file open, so the firmware dropped it.
 *
 * On a `WRITE` it means a block went nowhere, which makes it the one `PFT:` line a host must not
 * treat as advisory. See [MbpRs] on why this and [MbpUnknownPacketType] are two types.
 *
 * ```
 * MbpTransferInvalid.decode("PFT:invalid")   // MbpTransferInvalid
 * ```
 */
object MbpTransferInvalid : MbpRs<MbpTransferInvalid>, GRsDecoder<MbpTransferInvalid> {

    override fun encode(): String = "PFT:invalid"

    override fun decode(line: String): MbpTransferInvalid? =
        if (line.equalsAscii("PFT:invalid")) this else null
}

/**
 * `PTF:invalid` - the firmware does not know the file-transfer packet type that was sent. Note the
 * transposed letters: that is the spelling `binary_stream.h` writes on its `default` branch, and
 * re-encoding it as `PFT:` would produce a line no printer sends.
 *
 * Reaching this means the host and the firmware disagree about the protocol - a newer host talking
 * to an older build, say - so there is nothing to retry.
 *
 * ```
 * MbpUnknownPacketType.decode("PTF:invalid")   // MbpUnknownPacketType
 * ```
 */
object MbpUnknownPacketType : MbpRs<MbpUnknownPacketType>, GRsDecoder<MbpUnknownPacketType> {

    override fun encode(): String = "PTF:invalid"

    override fun decode(line: String): MbpUnknownPacketType? =
        if (line.equalsAscii("PTF:invalid")) this else null
}

/**
 * Every reply the binary protocol can send. One pass down the list for one line.
 *
 * Kept out of `MarlinRsDecoder` on purpose - see [MbpRs] for the `rs<n>` collision that forces it.
 * A host in binary mode runs this decoder first and falls back to `MarlinRsDecoder` for whatever it
 * declines, which is how the ASCII `ok` that answers `M28 B1` still gets read.
 *
 * ```
 * MbpRsDecoder.decode("PFT:success")   // MbpTransferSuccess
 * MbpRsDecoder.decode("wait")          // null
 * ```
 */
object MbpRsDecoder : GRsDecoder<MbpRs<*>> {

    /** The whole family, in the order [decode] tries them; they partition, so the order is free. */
    val decoders: List<GRsDecoder<MbpRs<*>>> = listOf(
        MbpOk,
        MbpResend,
        MbpStreamSync,
        MbpFatalError,
        MbpTransferVersion,
        MbpTransferSuccess,
        MbpTransferFail,
        MbpTransferBusy,
        MbpTransferIoError,
        MbpTransferInvalid,
        MbpUnknownPacketType,
    )

    override fun decode(line: String): MbpRs<*>? {
        for (i in 0 until decoders.size) {
            val reply = decoders[i].decode(line)
            if (reply != null) return reply
        }
        return null
    }
}

/**
 * The shape the three one-field stream replies share: a two-letter keyword, then the sync id and
 * nothing else. Written once here rather than three times, and strict on purpose - the firmware
 * writes `SERIAL_ECHOLNPGM("ok", sync)` with no separator at all, so a blank or a sign in the middle
 * means the line came from somewhere else.
 *
 * Spec 9: an id outside `0..255` cannot have been written by a `uint8_t`, so it declines.
 */
private fun syncAfter(line: String, keyword: String): Int? {
    val text = line.asciiTrim()
    if (!text.startsWithAscii(keyword)) return null
    val end = text.digitsEnd(keyword.length)
    if (end == keyword.length || end != text.length) return null
    val sync = text.substring(keyword.length, end).toIntOrNull() ?: return null
    return if (sync > 0xff) null else sync
}
