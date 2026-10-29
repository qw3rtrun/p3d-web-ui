package org.qw3rtrun.p3d.g.marlin.event

import java.math.BigDecimal
import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.numberEnd

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
 * machine with no extruder, and a delta reports `Count A: B: C:` instead of `X: Y: Z:`. [encode]
 * writes the axes in `X Y Z I J K U V W E A B C` order - `E` last, the way Marlin writes it, and
 * `A`/`B`/`C` for the delta and SCARA count labels.
 *
 * **The whole line or nothing**, for the same reason [TemperatureRs] scans that way: a line that
 * merely contains an `X:` is not a position report, and half-reading one loses the part that was not
 * understood without telling anyone. Everything after `Count` is stepper positions, and there is only
 * one `Count`. Spec 9: a step count too wide for `Int` is malformed input off the wire, so the line
 * does not match. A bare `Count X:0` is not a position report, and neither is an empty one. `X` is
 * the anchor: Marlin's `report_logical_position` always writes at least X, Y and Z, and without an
 * anchor this would claim `T:93.2 B:22.9` - the target-less temperature report the RepRap spec shows -
 * as a position on axes T and B.
 *
 * ```
 * PositionRs.decode("X:1.00 Y:2.00 Z:3.00 Count X:10")?.counts   // {X=10}
 * PositionRs.decode("T:93.2 B:22.9")                             // null
 * ```
 *
 * @property axes the logical position, per axis letter, with the digits the machine wrote
 * @property counts the stepper position in steps, per axis label; empty when the line has no `Count`
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
data class PositionRs(
    val axes: Map<Char, BigDecimal>,
    val counts: Map<Char, Int> = emptyMap(),
) : GEventRs<PositionRs> {

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

        private val AXIS_ORDER = "XYZIJKUVWEABC".toCharArray()

        /**
         * The walk was already here - the pattern only ever read **one field at a time**, anchored
         * at the cursor with `find(at)` and `matcher.start() != at`. So what comes out is the field
         * reader, not the loop.
         *
         * Note there is no case folding anywhere in this decoder, and there was none in the
         * pattern either: axis letters and `Count` are uppercase on every firmware that writes a
         * position report, and folding would start claiming lines that merely look like fields.
         */
        override fun decode(line: String): PositionRs? {
            val rest = line.asciiTrim()
            if (rest.isEmpty()) return null
            val axes = LinkedHashMap<Char, BigDecimal>()
            val counts = LinkedHashMap<Char, Int>()
            var inCounts = false
            var at = 0
            while (at < rest.length) {
                if (rest[at] == ' ' || rest[at] == '\t') {
                    at++
                    continue
                }
                if (rest.startsWith(COUNT, at)) {
                    if (inCounts) return null
                    inCounts = true
                    at += COUNT.length
                    continue
                }

                val axis = rest[at]
                if (!isAxis(axis)) return null
                var i = at + 1
                if (i >= rest.length || rest[i] != ':') return null
                i = rest.blanksEnd(i + 1)

                val end = rest.numberEnd(i)
                if (end < 0) return null
                val value = rest.substring(i, end)

                if (inCounts) {
                    val steps = value.toIntOrNull() ?: return null
                    if (counts.put(axis, steps) != null) return null
                } else {
                    val parsed = value.toBigDecimalOrNull() ?: return null
                    if (axes.put(axis, parsed) != null) return null
                }
                at = end
            }
            if (axes.isEmpty()) return null
            if (!axes.containsKey('X')) return null
            if (inCounts && counts.isEmpty()) return null
            return PositionRs(axes, counts)
        }

        /** The letters Marlin reports a position in, in the order it writes them. */
        private fun isAxis(c: Char): Boolean {
            for (axis in AXIS_ORDER) {
                if (axis == c) return true
            }
            return false
        }

        private const val COUNT = "Count"
    }
}
