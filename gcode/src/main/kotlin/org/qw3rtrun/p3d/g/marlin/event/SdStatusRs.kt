package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.code.dsl.GRsDecoder
import org.qw3rtrun.p3d.g.code.dsl.GRs
import java.util.regex.Pattern

/**
 * `M27`'s answer: either the machine is printing from its card, or it is not.
 *
 * Two shapes for one question, so they share an interface the way the `ok` replies do. Decode with
 * [SdStatusRsDecoder] when you only want to know which.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface SdStatusRs<D : SdStatusRs<D>> : GRs<D>

/**
 * `SD printing byte <position>/<size>` - how far into the file on the card the machine has read.
 *
 * Both numbers are `uint32_t` in the firmware, so they are [Long] here: a file over 2 GB is
 * unlikely but the type should not be the thing that decides.
 */
data class SdPrinting(val position: Long, val size: Long) : SdStatusRs<SdPrinting> {

    override fun encode(): String = "SD printing byte $position/$size"

    companion object : GRsDecoder<SdPrinting> {

        private val PATTERN = Pattern.compile(
            "^SD printing byte[ \t]*([0-9]+)[ \t]*/[ \t]*([0-9]+)$",
            Pattern.CASE_INSENSITIVE
        )

        override fun match(line: String): Boolean {
            val matcher = PATTERN.matcher(line.trim())
            if (!matcher.matches()) return false
            // spec 9: a digit run too wide for Long is malformed input off the wire, not a crash.
            return matcher.group(1).toLongOrNull() != null && matcher.group(2).toLongOrNull() != null
        }

        override fun decodeParams(line: String): SdPrinting {
            val matcher = PATTERN.matcher(line.trim())
            require(matcher.matches()) { "not an SD status line: $line" }
            val position = matcher.group(1).toLongOrNull()
            val size = matcher.group(2).toLongOrNull()
            require(position != null && size != null) { "SD byte counts do not fit a Long: $line" }
            return SdPrinting(position, size)
        }
    }
}

/** `Not SD printing` - carries nothing, so it is its own decoder. */
object SdNotPrinting : SdStatusRs<SdNotPrinting>, GRsDecoder<SdNotPrinting> {

    override fun encode(): String = "Not SD printing"

    override fun match(line: String): Boolean =
        line.trim().equals("Not SD printing", ignoreCase = true)

    override fun decodeParams(line: String): SdNotPrinting = this
}

/** Either answer to `M27`. */
object SdStatusRsDecoder : GRsDecoder<SdStatusRs<*>> {

    override fun match(line: String): Boolean = SdPrinting.match(line) || SdNotPrinting.match(line)

    override fun decodeParams(line: String): SdStatusRs<*> =
        if (SdPrinting.match(line)) SdPrinting.decodeParams(line) else SdNotPrinting
}
