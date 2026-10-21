package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.digitsEnd
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * `M27`'s answer: either the machine is printing from its card, or it is not.
 *
 * Two shapes for one question, so they share an interface the way the `ok` replies do. Decode with
 * [SdStatusRsDecoder] when you only want to know which.
 *
 * ```
 * SdStatusRsDecoder.decode("Not SD printing")   // SdNotPrinting
 * ```
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface SdStatusRs<D : SdStatusRs<D>> : GEventRs<D>

/**
 * `SD printing byte <position>/<size>` - how far into the file on the card the machine has read.
 *
 * Both numbers are `uint32_t` in the firmware, so they are [Long] here: a file over 2 GB is
 * unlikely but the type should not be the thing that decides. Spec 9: a digit run too wide even for
 * `Long` is malformed input off the wire, not a crash, so it does not match.
 *
 * ```
 * SdPrinting.decode("SD printing byte 1234/56789")   // SdPrinting(1234, 56789)
 * ```
 *
 * @property position the byte offset read so far
 * @property size the file's size in bytes
 */
data class SdPrinting(val position: Long, val size: Long) : SdStatusRs<SdPrinting> {

    override fun encode(): String = "SD printing byte $position/$size"

    companion object : GRsDecoder<SdPrinting> {

        private const val PREFIX = "SD printing byte"

        override fun match(line: String): Boolean = scan(line) != null

        override fun decodeParams(line: String): SdPrinting =
            requireNotNull(scan(line)) { "not an SD status line: $line" }

        /**
         * `SD printing byte <position>/<size>`, or null when the line is not one.
         *
         * Some builds write no space after `byte` and some write one, so the blanks are optional
         * in all three positions - which is what the pattern this replaced said with `[ 	]*`. A
         * count too wide for a `Long` is *absence*, not a throw: a host reads what the printer
         * sent and does not get to crash over it.
         */
        private fun scan(line: String): SdPrinting? {
            val text = line.trim()
            if (!text.startsWithAscii(PREFIX)) return null

            var i = text.blanksEnd(PREFIX.length)
            val positionEnd = text.digitsEnd(i)
            if (positionEnd == i) return null
            val position = text.substring(i, positionEnd).toLongOrNull() ?: return null

            i = text.blanksEnd(positionEnd)
            if (i >= text.length || text[i] != '/') return null

            i = text.blanksEnd(i + 1)
            val sizeEnd = text.digitsEnd(i)
            if (sizeEnd == i || sizeEnd != text.length) return null
            val size = text.substring(i, sizeEnd).toLongOrNull() ?: return null

            return SdPrinting(position, size)
        }
    }
}

/**
 * `Not SD printing` - carries nothing, so it is its own decoder.
 *
 * ```
 * SdNotPrinting.decode("Not SD printing")   // SdNotPrinting
 * ```
 */
object SdNotPrinting : SdStatusRs<SdNotPrinting>, GRsDecoder<SdNotPrinting> {

    override fun encode(): String = "Not SD printing"

    override fun match(line: String): Boolean =
        line.trim().equals("Not SD printing", ignoreCase = true)

    override fun decodeParams(line: String): SdNotPrinting = this
}

/**
 * Either answer to `M27`.
 *
 * ```
 * SdStatusRsDecoder.decode("SD printing byte 1234/56789")   // SdPrinting(1234, 56789)
 * ```
 */
object SdStatusRsDecoder : GRsDecoder<SdStatusRs<*>> {

    override fun match(line: String): Boolean = SdPrinting.match(line) || SdNotPrinting.match(line)

    override fun decodeParams(line: String): SdStatusRs<*> =
        if (SdPrinting.match(line)) SdPrinting.decodeParams(line) else SdNotPrinting
}
