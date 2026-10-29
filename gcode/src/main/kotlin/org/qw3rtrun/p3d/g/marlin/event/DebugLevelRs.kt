package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.sameAscii
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * `echo:DEBUG:<flags>` - `M111`'s answer, saying which debug channels the firmware is now writing.
 *
 * It arrives on the echo channel, so it is an [EchoRs] as well as a reply in its own right. A host
 * sends `M111` to turn `ECHO` on before replaying a file and wants to know it took, or reads the
 * answer to show the operator what the machine is currently logging.
 *
 * **The flags are strings, not an enum.** Marlin's own set is `ECHO`, `INFO`, `ERRORS`, `DRYRUN`,
 * `COMMUNICATION`, `LEVELING` and `MESH_ADJUST`, but the bit mask is a build-time thing and forks
 * add their own; an enum would turn a channel this code has not heard of into a line that does not
 * decode at all, which is worse than a name it cannot interpret. Compare against the constants when
 * you need to branch.
 *
 * **No flags is `off`**, which is what the firmware writes and what an empty [flags] encodes back
 * to - `M111 S0` answers `echo:DEBUG:off`, and that round-trips rather than becoming `echo:DEBUG:`.
 *
 * Captured live from MarlinSim (bugfix-2.1.x): `M111 S0` gives `off`, `S8` gives `DRYRUN`, `S38`
 * gives `INFO,ERRORS`, and `S247` gives `ECHO,INFO,ERRORS,COMMUNICATION`.
 *
 * ```
 * DebugLevelRs.decode("echo:DEBUG:INFO,ERRORS")   // DebugLevelRs([INFO, ERRORS])
 * DebugLevelRs.decode("echo:DEBUG:off")           // DebugLevelRs([])
 * DebugLevelRs(setOf("DRYRUN")).encode()          // "echo:DEBUG:DRYRUN"
 * ```
 *
 * @property flags the channels now being written, empty when the firmware answered `off`
 * @see <a href="https://marlinfw.org/docs/gcode/M111.html">MarlinFirmware M111 doc</a>
 */
data class DebugLevelRs(val flags: Set<String>) : EchoRs<DebugLevelRs> {

    override fun encode(): String =
        EchoMessage.PREFIX + BODY + if (flags.isEmpty()) OFF else flags.joinToString(",")

    companion object : GRsDecoder<DebugLevelRs> {

        const val ECHO = "ECHO"

        const val INFO = "INFO"

        const val ERRORS = "ERRORS"

        const val DRYRUN = "DRYRUN"

        const val COMMUNICATION = "COMMUNICATION"

        const val LEVELING = "LEVELING"

        const val MESH_ADJUST = "MESH_ADJUST"

        private const val BODY = "DEBUG:"

        private const val OFF = "off"

        /**
         * The flags behind `echo:DEBUG:`, or null when the line is not one.
         *
         * A comma-separated list, taken verbatim with the blanks around each name dropped: the
         * firmware writes no spaces, but a fork that did would otherwise produce a flag called
         * `" ERRORS"` that compares equal to nothing. An empty list, or the word `off`, is no
         * flags - and a trailing comma does not invent one.
         */
        override fun decode(line: String): DebugLevelRs? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(EchoMessage.PREFIX)) return null

            val at = text.blanksEnd(EchoMessage.PREFIX.length)
            if (!text.startsWithAscii(BODY, at)) return null

            val list = text.substring(at + BODY.length).asciiTrim()
            if (list.isEmpty()) return null
            if (list.sameAscii(OFF)) return DebugLevelRs(emptySet())

            val flags = LinkedHashSet<String>()
            var i = 0
            while (i < list.length) {
                var end = i
                while (end < list.length && list[end] != ',') end++
                val name = list.substring(i, end).asciiTrim()
                if (name.isNotEmpty()) flags.add(name)
                i = end + 1
            }
            return if (flags.isEmpty()) null else DebugLevelRs(flags)
        }
    }
}
