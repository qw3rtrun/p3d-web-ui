package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.code.dsl.GRsDecoder
import org.qw3rtrun.p3d.g.code.dsl.GRs
import java.math.BigDecimal
import java.util.regex.Pattern

/**
 * The position report - `M114`'s answer and `M154`'s auto-report.
 *
 * ```
 * X:0.00 Y:0.00 Z:0.00 E:0.00 Count X:0 Y:0 Z:0
 * ```
 *
 * Two halves that mean different things. [axes] is where the machine believes the tool is, in the
 * logical coordinates a host thinks in. [counts] is the stepper position in steps, which is what
 * the planner actually commanded - they disagree exactly when something slipped, which is the whole
 * reason Marlin prints both.
 *
 * Both are kept as maps rather than named fields because the axis set is a compile-time decision:
 * `I`, `J`, `K`, `U`, `V`, `W` exist on machines with more than three axes, `E` is absent on a
 * machine with no extruder, and a delta reports `Count A: B: C:` instead of `X: Y: Z:`.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
data class PositionRs(
    val axes: Map<Char, BigDecimal>,
    val counts: Map<Char, Int> = emptyMap(),
) : GRs<PositionRs> {

    override fun encode(): String {
        val out = StringBuilder()
        for (axis in AXIS_ORDER) {
            val value = axes[axis] ?: continue
            if (out.isNotEmpty()) out.append(' ')
            out.append(axis).append(':').append(value.toPlainString())
        }
        if (counts.isNotEmpty()) {
            out.append(" Count")
            for (axis in AXIS_ORDER) {
                val value = counts[axis] ?: continue
                out.append(' ').append(axis).append(':').append(value)
            }
        }
        return out.toString()
    }

    companion object : GRsDecoder<PositionRs> {

        // `E` last, the way Marlin writes it, and `A`/`B`/`C` for the delta and SCARA count labels.
        private val AXIS_ORDER = "XYZIJKUVWEABC".toCharArray()

        private val FIELD = Pattern.compile("([XYZIJKUVWEABC]):[ \t]*([-+]?(?>[0-9]*\\.)?[0-9]+)")

        override fun match(line: String): Boolean = scan(line) != null

        override fun decodeParams(line: String): PositionRs {
            val scanned = scan(line)
            require(scanned != null) { "not a position report: $line" }
            return scanned
        }

        /**
         * The whole line or nothing, for the same reason [TemperatureRs] scans that way: a line
         * that merely contains an `X:` is not a position report, and half-reading one loses the
         * part that was not understood without telling anyone.
         */
        private fun scan(line: String): PositionRs? {
            val rest = line.trim()
            if (rest.isEmpty()) return null
            val axes = LinkedHashMap<Char, BigDecimal>()
            val counts = LinkedHashMap<Char, Int>()
            var inCounts = false
            var at = 0
            val matcher = FIELD.matcher(rest)
            while (at < rest.length) {
                if (rest[at] == ' ' || rest[at] == '\t') {
                    at++
                    continue
                }
                if (rest.startsWith(COUNT, at)) {
                    // Everything after `Count` is stepper positions, and there is only one `Count`.
                    if (inCounts) return null
                    inCounts = true
                    at += COUNT.length
                    continue
                }
                if (!matcher.find(at) || matcher.start() != at) return null
                val axis = matcher.group(1)[0]
                if (inCounts) {
                    // spec 9: a step count too wide for Int is malformed input off the wire.
                    val steps = matcher.group(2).toIntOrNull() ?: return null
                    if (counts.put(axis, steps) != null) return null
                } else {
                    val value = matcher.group(2).toBigDecimalOrNull() ?: return null
                    if (axes.put(axis, value) != null) return null
                }
                at = matcher.end()
            }
            // A bare `Count X:0` is not a position report, and neither is an empty one.
            if (axes.isEmpty()) return null
            // `X` is the anchor. Marlin's report_logical_position always writes at least X, Y and
            // Z, and without an anchor this claims `T:93.2 B:22.9` - the target-less temperature
            // report the RepRap spec shows - as a position on axes T and B.
            if (!axes.containsKey('X')) return null
            if (inCounts && counts.isEmpty()) return null
            return PositionRs(axes, counts)
        }

        private const val COUNT = "Count"
    }
}
