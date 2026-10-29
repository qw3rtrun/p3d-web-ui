package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.digitsEnd
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * `echo:Cold extrudes are <enabled|disabled> (min temp <n>C)` - `M302`'s answer.
 *
 * Both halves matter to a host. [enabled] says whether the firmware will extrude below
 * [minTempC] at all, and [minTempC] is the threshold it compares against - so a move that
 * extruded nothing is explained by this reply and by nothing else the machine says.
 *
 * It arrives on the echo channel, so it is an [EchoRs] too. The related
 * [ColdExtrusionPrevented] is the notice that fires when a move actually is refused.
 *
 * Captured live from MarlinSim (bugfix-2.1.x), which answers `M302` with
 * `echo:Cold extrudes are disabled (min temp 170C)` after `M302 P0`, `enabled` after `M302 P1`,
 * and `(min temp 0C)` after `M302 S0`.
 *
 * ```
 * ColdExtrusionRs.decode("echo:Cold extrudes are enabled (min temp 170C)")   // (true, 170)
 * ColdExtrusionRs(false, 170).encode()   // "echo:Cold extrudes are disabled (min temp 170C)"
 * ```
 *
 * @property enabled whether extruding below [minTempC] is allowed
 * @property minTempC the threshold in degrees Celsius, as the firmware wrote it
 * @see <a href="https://marlinfw.org/docs/gcode/M302.html">MarlinFirmware M302 doc</a>
 */
data class ColdExtrusionRs(val enabled: Boolean, val minTempC: Int) : EchoRs<ColdExtrusionRs> {

    override fun encode(): String =
        EchoMessage.PREFIX + BODY + (if (enabled) ENABLED else DISABLED) + MIN_TEMP + minTempC + SUFFIX

    companion object : GRsDecoder<ColdExtrusionRs> {

        private const val BODY = "Cold extrudes are "

        private const val ENABLED = "enabled"

        private const val DISABLED = "disabled"

        private const val MIN_TEMP = " (min temp "

        private const val SUFFIX = "C)"

        override fun match(line: String): Boolean = scan(line) != null

        override fun decodeParams(line: String): ColdExtrusionRs =
            requireNotNull(scan(line)) { "not a cold extrusion report: $line" }

        /**
         * The state and the threshold, or null when the line is not this report.
         *
         * The whole line is matched rather than scanned for a number: `(min temp 170C)` is the
         * only number on it, but a reply that said something else entirely about cold extrusion
         * would otherwise decode to a threshold it never mentioned.
         */
        private fun scan(line: String): ColdExtrusionRs? {
            val text = line.trim()
            if (!text.startsWithAscii(EchoMessage.PREFIX)) return null

            // Marlin writes some of these with a blank after the colon and some without, since the
            // message and the `echo:` come from separate calls: `echo: cold extrusion prevented`
            // but `echo:Cold extrudes are ...`. Cross it either way.
            var i = text.blanksEnd(EchoMessage.PREFIX.length)
            if (!text.startsWithAscii(BODY, i)) return null
            i += BODY.length
            val enabled = when {
                text.startsWithAscii(ENABLED, i) -> { i += ENABLED.length; true }
                text.startsWithAscii(DISABLED, i) -> { i += DISABLED.length; false }
                else -> return null
            }

            if (!text.startsWithAscii(MIN_TEMP, i)) return null
            i = text.blanksEnd(i + MIN_TEMP.length)

            val end = text.digitsEnd(i)
            if (end == i) return null
            val minTemp = text.substring(i, end).toIntOrNull() ?: return null

            if (!text.startsWithAscii(SUFFIX, end) || end + SUFFIX.length != text.length) return null
            return ColdExtrusionRs(enabled, minTemp)
        }
    }
}

/**
 * `echo: cold extrusion prevented` - a move was refused because the hotend is below the threshold.
 *
 * Worth its own type for the same reason [UnknownCommand] is: the command was accepted by the wire
 * protocol, acknowledged with an `ok`, and then did nothing. A host that does not read this sees a
 * filament move that silently extruded nothing, and the `ok` that followed it tells it everything
 * went fine.
 *
 * Carries nothing - the line says only that it happened. [ColdExtrusionRs] is the report with the
 * state and the threshold in it.
 *
 * Observed live from MarlinSim while replaying the corpus with checking enabled - and note the
 * blank after the colon, which [ColdExtrusionRs] does not have. The two messages reach the serial
 * line through different calls in the firmware, so the spacing differs; the first live sweep read
 * this one as prose because the decoder did not cross it.
 *
 * ```
 * ColdExtrusionPrevented.decode("echo: cold extrusion prevented")   // ColdExtrusionPrevented
 * ColdExtrusionPrevented.encode()                                   // "echo: cold extrusion prevented"
 * ```
 */
object ColdExtrusionPrevented : EchoRs<ColdExtrusionPrevented>, GRsDecoder<ColdExtrusionPrevented> {

    private const val BODY = "cold extrusion prevented"

    override fun encode(): String = EchoMessage.PREFIX + " " + BODY

    override fun match(line: String): Boolean {
        val text = line.trim()
        if (!text.startsWithAscii(EchoMessage.PREFIX)) return false
        // The firmware writes a blank after the colon here and none before `Cold extrudes are`;
        // the live sweep read this one as prose until the blank was crossed.
        val i = text.blanksEnd(EchoMessage.PREFIX.length)
        return text.length - i == BODY.length && text.startsWithAscii(BODY, i)
    }

    override fun decodeParams(line: String): ColdExtrusionPrevented {
        require(match(line)) { "not a cold extrusion notice: $line" }
        return this
    }
}
