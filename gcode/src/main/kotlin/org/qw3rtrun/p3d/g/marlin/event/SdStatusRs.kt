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

        /**
         * `SD printing byte <position>/<size>`, or null when the line is not one.
         *
         * Some builds write no space after `byte` and some write one, so the blanks are optional
         * in all three positions - which is what the pattern this replaced said with `[ 	]*`. A
         * count too wide for a `Long` is *absence*, not a throw: a host reads what the printer
         * sent and does not get to crash over it.
         */
        override fun decode(line: String): SdPrinting? {
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

    override fun decode(line: String): SdNotPrinting? =
        if (line.trim().equals("Not SD printing", ignoreCase = true)) this else null
}

/**
 * `Current file: <name>` - `M27 C`'s answer, naming the file the card is positioned on.
 *
 * `M27` says how far through the machine is; `M27 C` says what it is reading. The firmware writes
 * `(no file)` when there is none selected, and [file] is null for that rather than the literal
 * text, so a caller branches on absence instead of comparing against a sentinel string.
 *
 * Captured live from MarlinSim (bugfix-2.1.x) with an empty card, which is the `(no file)` half;
 * the named half is the same line with a filename in place of that text.
 *
 * ```
 * SdCurrentFile.decode("Current file: (no file)")      // SdCurrentFile(null)
 * SdCurrentFile.decode("Current file: jingle.gco")     // SdCurrentFile("jingle.gco")
 * SdCurrentFile(null).encode()                         // "Current file: (no file)"
 * ```
 *
 * @property file the file the card is positioned on, or null when there is none
 */
data class SdCurrentFile(val file: String?) : SdStatusRs<SdCurrentFile> {

    override fun encode(): String = PREFIX + (file ?: NO_FILE)

    companion object : GRsDecoder<SdCurrentFile> {

        private const val PREFIX = "Current file: "

        private const val NO_FILE = "(no file)"

        /** The filename behind the prefix, or null when the line is not this reply. */
        override fun decode(line: String): SdCurrentFile? {
            val text = line.trim()
            if (!text.startsWithAscii(PREFIX)) return null

            val name = text.substring(PREFIX.length).trim()
            if (name.isEmpty()) return null
            return SdCurrentFile(if (name == NO_FILE) null else name)
        }
    }
}

/**
 * Any of `M27`'s answers: how far through, not printing at all, or which file.
 *
 * ```
 * SdStatusRsDecoder.decode("SD printing byte 1234/56789")   // SdPrinting(1234, 56789)
 * SdStatusRsDecoder.decode("Current file: (no file)")       // SdCurrentFile(null)
 * ```
 */
object SdStatusRsDecoder : GRsDecoder<SdStatusRs<*>> {

    override fun decode(line: String): SdStatusRs<*>? =
        SdPrinting.decode(line) ?: SdCurrentFile.decode(line) ?: SdNotPrinting.decode(line)
}
