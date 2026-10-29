package org.qw3rtrun.p3d.g.protocol


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
interface OkRs<D : OkRs<D>> : GProtoRs<D>

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
    override fun decode(line: String): SimpleOkRs? =
        if (line.equalsAscii("ok")) this else null
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
        private const val OK = "ok"

        /**
         * `ok [N<n>] <P|B><n> <P|B><n>`, in one pass, or null when the line is not one.
         *
         * Four rules the pattern stated and this states in order:
         *
         * - **the separator is a space, not a blank.** ` +` admitted no tab, and a tab between
         *   these fields is not something a firmware writes - [spaces] keeps that narrow rather
         *   than widening it to spec 2.1's pair by accident.
         * - **`N` comes first or not at all.** `ok B3 N100 P15` is not this reply.
         * - **the two counts name different fields.** `ok P15 P3` says nothing: one of the two
         *   numbers would have no home, and the record cannot represent it.
         * - **nothing follows the second count.** The pattern was read with `matches()`, so the
         *   line ends there.
         *
         * spec 9 throughout: a digit run too wide for an `Int` is malformed input off the wire, so
         * every conversion is `toIntOrNull` and a line this decoder declines rather than throws on.
         */
        override fun decode(line: String): AdvancedOkRs? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(OK)) return null

            var i = spaces(text, OK.length)
            if (i == OK.length) return null

            var lineNumber: Int? = null
            if (i < text.length && (text[i] == 'N' || text[i] == 'n')) {
                val end = text.digitsEnd(i + 1)
                if (end == i + 1) return null
                lineNumber = text.substring(i + 1, end).toIntOrNull() ?: return null
                val next = spaces(text, end)
                if (next == end) return null
                i = next
            }

            if (i >= text.length) return null
            val firstIsPlanner = isPlanner(text[i])
            if (!firstIsPlanner && !isQueue(text[i])) return null
            var end = text.digitsEnd(i + 1)
            if (end == i + 1) return null
            val first = text.substring(i + 1, end).toIntOrNull() ?: return null

            i = spaces(text, end)
            if (i == end || i >= text.length) return null
            val secondIsPlanner = isPlanner(text[i])
            if (!secondIsPlanner && !isQueue(text[i])) return null
            if (firstIsPlanner == secondIsPlanner) return null
            end = text.digitsEnd(i + 1)
            if (end == i + 1 || end != text.length) return null
            val second = text.substring(i + 1, end).toIntOrNull() ?: return null

            return if (firstIsPlanner) {
                AdvancedOkRs(first, second, lineNumber)
            } else {
                AdvancedOkRs(second, first, lineNumber)
            }
        }

        /** The index past the run of **spaces** at [from] - the pattern's ` +`, tab excluded. */
        private fun spaces(text: String, from: Int): Int {
            var i = from
            while (i < text.length && text[i] == ' ') i++
            return i
        }

        private fun isPlanner(c: Char) = c == 'P' || c == 'p'

        private fun isQueue(c: Char) = c == 'B' || c == 'b'
    }
}

/**
 * Either kind of `ok` line: the bare one first, then the advanced one.
 *
 * ```
 * OkRsDecoder.decode("ok")                // SimpleOkRs
 * OkRsDecoder.decode("ok N100 P15 B3")    // AdvancedOkRs(15, 3, 100)
 * ```
 */
object OkRsDecoder : GRsDecoder<OkRs<*>> {

    /**
     * The bare `ok` first, because it is by far the commonest line on the link and the cheapest to
     * decide - a trim and a comparison, against the advanced form's field walk.
     *
     * This used to ask [SimpleOkRs] three times for a bare `ok`: twice in `match` through the `||`
     * and once more in `decodeParams`.
     */
    override fun decode(line: String): OkRs<*>? =
        SimpleOkRs.decode(line) ?: AdvancedOkRs.decode(line)
}