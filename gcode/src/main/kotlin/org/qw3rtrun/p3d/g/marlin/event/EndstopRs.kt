package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.equalsAscii
import org.qw3rtrun.p3d.g.protocol.sameAscii
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * `M119`'s answer, which is **several lines**:
 *
 * ```
 * Reporting endstop status
 * x_min: open
 * y_min: TRIGGERED
 * z_probe: open
 * ```
 *
 * [GRsDecoder] reads one line, so this is modelled as one event per line - [EndstopReportHeader]
 * and then an [EndstopStateRs] each - and the caller assembles the block if it needs it whole.
 *
 * That is the cheap answer and it is honest about the interface rather than pretending a decoder
 * has memory. It works here because each line stands on its own: `x_min: open` means the same thing
 * whether or not its header arrived. It will **not** work for `M20`'s file list, where a list
 * without its terminator is wrong rather than merely partial - see todo 12 on the multi-line
 * problem, which is still open.
 *
 * ```
 * EndstopReportHeader.decode("Reporting endstop status")   // EndstopReportHeader
 * ```
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
object EndstopReportHeader : GEventRs<EndstopReportHeader>, GRsDecoder<EndstopReportHeader> {

    override fun encode(): String = "Reporting endstop status"

    override fun decode(line: String): EndstopReportHeader? =
        if (line.equalsAscii("Reporting endstop status")) this else null
}

/**
 * One endstop's line: `x_min: open` or `z_probe: TRIGGERED`.
 *
 * The name is matched against the shapes Marlin actually writes rather than against `\w+`, because
 * `<word>: <word>` is far too common a shape on this wire to claim - `busy: processing` fits it.
 * Marlin's own set, from `Endstops::report_states`: `<axis>_min` / `<axis>_max` for axes `x`, `y`,
 * `z`, `a`..`w` and their duplicates `x2`, `y2`, `z2`, `z3`, `z4`, plus `z_probe`, `probe_en` and
 * `filament`.
 *
 * ```
 * EndstopStateRs.decode("x_min: TRIGGERED")     // EndstopStateRs("x_min", triggered = true)
 * EndstopStateRs.match("busy: processing")      // false
 * EndstopStateRs("z_probe", false).encode()     // "z_probe: open"
 * ```
 *
 * @property name the endstop's name, as Marlin wrote it
 * @property triggered true for `TRIGGERED`, false for `open`
 */
data class EndstopStateRs(val name: String, val triggered: Boolean) : GRs<EndstopStateRs> {

    override fun encode(): String = "$name: " + if (triggered) TRIGGERED else OPEN

    companion object : GRsDecoder<EndstopStateRs> {

        private const val TRIGGERED = "TRIGGERED"
        private const val OPEN = "open"

        /** The three endstops Marlin names outright, rather than by axis. */
        private val FIXED = arrayOf("z_probe", "probe_en", "filament")

        private const val MIN = "_min"
        private const val MAX = "_max"

        /**
         * `<name>: <state>`, or null when the line is not one.
         *
         * The name is checked against the shapes Marlin writes, not against "a word", because
         * `<word>: <word>` is far too common on this wire to claim - `busy: processing` fits it and
         * is somebody else's reply.
         */
        override fun decode(line: String): EndstopStateRs? {
            val text = line.asciiTrim()

            val nameEnd = nameEnd(text)
            if (nameEnd < 0) return null
            if (nameEnd >= text.length || text[nameEnd] != ':') return null

            val state = text.substring(text.blanksEnd(nameEnd + 1))
            val triggered = when {
                state.sameAscii(TRIGGERED) -> true
                state.sameAscii(OPEN) -> false
                else -> return null
            }
            return EndstopStateRs(text.substring(0, nameEnd), triggered)
        }

        /**
         * Where the endstop's name ends, or -1 when [text] does not open with one.
         *
         * Two shapes, the fixed names first because `z_probe` would otherwise be read as axis `z`
         * and fail on the suffix. The axis form is a letter, **one** optional character, and
         * `_min` or `_max`: that covers `x_min`, the duplicates `x2`, `z3`, `z4`, and whatever
         * `a`..`w` a delta or an IDEX reports. Two extra characters is not a name Marlin writes.
         */
        private fun nameEnd(text: String): Int {
            for (fixed in FIXED) {
                if (text.startsWithAscii(fixed)) return fixed.length
            }

            if (text.isEmpty() || !isLetter(text[0])) return -1
            var i = 1
            if (i < text.length && (isLetter(text[i]) || isDigit(text[i]))) i++
            if (text.startsWithAscii(MIN, i) || text.startsWithAscii(MAX, i)) return i + MIN.length
            return -1
        }

        private fun isLetter(c: Char) = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

        private fun isDigit(c: Char) = c >= '0' && c <= '9'
    }
}

/**
 * Either line of an `M119` report.
 *
 * ```
 * EndstopRsDecoder.decode("y_max: open")   // EndstopStateRs("y_max", triggered = false)
 * ```
 */
object EndstopRsDecoder : GRsDecoder<GRs<*>> {

    override fun decode(line: String): GRs<*>? =
        EndstopReportHeader.decode(line) ?: EndstopStateRs.decode(line)
}
