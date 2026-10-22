package org.qw3rtrun.p3d.g.protocol

import org.qw3rtrun.p3d.api.msg.OKReceivedEvent
import java.util.regex.Pattern

/**
 * `ok` - the machine has accepted the last line into its queue. The acknowledgement a host's send
 * window advances on, and an `OKReceivedEvent` for the event pipeline.
 *
 * Two shapes share it: the bare [SimpleOkRs], and [AdvancedOkRs] with buffer counts. A temperature
 * report that rides on an `ok` is one too (`OkTemperatureRs`). Decode the family with [OkRsDecoder].
 *
 * ```
 * OkRsDecoder.decode("ok")          // SimpleOkRs
 * OkRsDecoder.decode("ok P15 B3")   // AdvancedOkRs(15, 3)
 * ```
 */
interface OkRs<D : OkRs<D>> : GProtoRs<D>, OKReceivedEvent

/**
 * A bare `ok`. Carries nothing, so it is a single object that is also its own decoder; the match is
 * trimmed and case-insensitive.
 *
 * ```
 * SimpleOkRs.match(" OK ")   // true
 * SimpleOkRs.encode()        // "ok"
 * ```
 */
object SimpleOkRs : OkRs<SimpleOkRs>, GRsDecoder<SimpleOkRs> {
    override fun encode(): String = "ok"
    override fun match(line: String): Boolean = line.trim().equals("ok", ignoreCase = true)
    override fun decodeParams(line: String): SimpleOkRs = this
}

/**
 * `ok [N<line>] P<planner> B<block>` - an `ok` carrying the line number it acknowledges (`N`, when
 * the host numbered the line) and the planner (`P`) and block-queue (`B`) counts.
 *
 * **The line number comes first**, which is what Marlin writes: `ok_to_send()` echoes the `N` field
 * off the command it is acknowledging before appending ` P` and ` B`. This read it last for a
 * while, and `encode()` wrote it last to match, so the type round-tripped with itself and matched
 * nothing a printer sends - every acknowledgement of a numbered line fell through every decoder,
 * which a live probe against MarlinSim found and the test suite could not.
 *
 * `P` and `B` are read in either order, and exactly one of each; [encode] normalises to `P` then
 * `B`. An absent line number stays absent: it decodes to `null` and not to `-1`, because the property
 * is nullable and [encode] writes out any non-null line number, so a `-1` re-encoded to
 * `ok N-1 P15 B3` - a line nothing sends and this decoder rejects. Absent on the wire has to stay
 * absent in the value.
 *
 * ```
 * AdvancedOkRs.decode("ok B3 P15")         // AdvancedOkRs(planner = 15, blockQueue = 3)
 * AdvancedOkRs.decode("ok N2 P15 B3")      // AdvancedOkRs(15, 3, 2)
 * AdvancedOkRs(15, 3, 100).encode()        // "ok N100 P15 B3"
 * ```
 *
 * @property planner the value of the `P` field
 * @property blockQueue the value of the `B` field
 * @property lineNumber the value of the `N` field, or null when the reply carries none
 */
data class AdvancedOkRs(
    val planner: Int,
    val blockQueue: Int,
    val lineNumber: Int? = null
) : OkRs<AdvancedOkRs> {

    override fun encode(): String =
        "ok" + (if (lineNumber != null) " N$lineNumber" else "") + " P$planner B$blockQueue"

    companion object : GRsDecoder<AdvancedOkRs> {
        private val ADVANCED_OK_PATTERN = Pattern.compile(
            "^[oO][kK](?> +([Nn])([0-9]+))? +([PpBb])([0-9]+) +([PpBb])([0-9]+)"
        )

        override fun match(line: String): Boolean {
            val matcher = ADVANCED_OK_PATTERN.matcher(line.trim())
            if (!matcher.matches()) return false
            if (matcher.group(3).equals(matcher.group(5), ignoreCase = true)) return false
            if (matcher.group(4).toIntOrNull() == null) return false
            if (matcher.group(6).toIntOrNull() == null) return false
            if (matcher.group(1) != null && matcher.group(2).toIntOrNull() == null) return false
            return true
        }

        override fun decodeParams(line: String): AdvancedOkRs {
            val matcher = ADVANCED_OK_PATTERN.matcher(line.trim())
            require(matcher.matches())
            var planner = -1
            var blockQueue = -1
            val first = matcher.group(4).toInt()
            val second = matcher.group(6).toInt()
            when (matcher.group(3)) {
                "P", "p" -> planner = first
                "B", "b" -> blockQueue = first
            }
            when (matcher.group(5)) {
                "B", "b" -> blockQueue = second
                "P", "p" -> planner = second
            }
            val lineNumber = if (matcher.group(1) != null) {
                matcher.group(2).toInt()
            } else {
                null
            }

            return AdvancedOkRs(planner, blockQueue, lineNumber)
        }
    }
}

/**
 * Either kind of `ok` line: the bare one first, then the advanced one.
 *
 * ```
 * OkRsDecoder.decode("ok")                // SimpleOkRs
 * OkRsDecoder.decode("ok P15 B3 N100")    // AdvancedOkRs(15, 3, 100)
 * ```
 */
object OkRsDecoder : GRsDecoder<OkRs<*>> {
    override fun match(line: String) = SimpleOkRs.match(line) || AdvancedOkRs.match(line)

    override fun decodeParams(line: String) =
        if (SimpleOkRs.match(line)) SimpleOkRs else AdvancedOkRs.decodeParams(line)
}