package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.code.dsl.GRsDecoder
import org.qw3rtrun.p3d.g.code.dsl.GRs
import java.util.regex.Pattern

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
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
object EndstopReportHeader : GRs<EndstopReportHeader>, GRsDecoder<EndstopReportHeader> {

    override fun encode(): String = "Reporting endstop status"

    override fun match(line: String): Boolean =
        line.trim().equals("Reporting endstop status", ignoreCase = true)

    override fun decodeParams(line: String): EndstopReportHeader = this
}

/**
 * One endstop's line: `x_min: open` or `z_probe: TRIGGERED`.
 *
 * The name is matched against the shapes Marlin actually writes rather than against `\w+`, because
 * `<word>: <word>` is far too common a shape on this wire to claim - `busy: processing` fits it.
 * Marlin's own set, from `Endstops::report_states`: `<axis>_min` / `<axis>_max` for axes `x`, `y`,
 * `z`, `a`..`w` and their duplicates `x2`, `y2`, `z2`, `z3`, `z4`, plus `z_probe`, `probe_en` and
 * `filament`.
 */
data class EndstopStateRs(val name: String, val triggered: Boolean) : GRs<EndstopStateRs> {

    override fun encode(): String = "$name: " + if (triggered) TRIGGERED else OPEN

    companion object : GRsDecoder<EndstopStateRs> {

        private const val TRIGGERED = "TRIGGERED"
        private const val OPEN = "open"

        private val PATTERN = Pattern.compile(
            "^([a-z][a-z0-9]?_(?>min|max)|z_probe|probe_en|filament):[ \t]*(TRIGGERED|open)$",
            Pattern.CASE_INSENSITIVE
        )

        override fun match(line: String): Boolean = PATTERN.matcher(line.trim()).matches()

        override fun decodeParams(line: String): EndstopStateRs {
            val matcher = PATTERN.matcher(line.trim())
            require(matcher.matches()) { "not an endstop state: $line" }
            return EndstopStateRs(matcher.group(1), matcher.group(2).equals(TRIGGERED, ignoreCase = true))
        }
    }
}

/** Either line of an `M119` report. */
object EndstopRsDecoder : GRsDecoder<GRs<*>> {

    override fun match(line: String): Boolean =
        EndstopReportHeader.match(line) || EndstopStateRs.match(line)

    override fun decodeParams(line: String): GRs<*> =
        if (EndstopReportHeader.match(line)) EndstopReportHeader else EndstopStateRs.decodeParams(line)
}
