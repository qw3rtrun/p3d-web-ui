package org.qw3rtrun.p3d.g.marlin.protocol

import org.qw3rtrun.p3d.g.code.dsl.GRS
import org.qw3rtrun.p3d.g.code.dsl.GRSDecoder
import java.util.regex.Pattern

/**
 * `busy: <reason>` - the machine cannot take commands from the serial interface right now.
 *
 * ```
 * busy: processing
 * busy: paused for user
 * ```
 *
 * The reason is kept as the text the machine sent rather than an enum. The spec names three
 * ([PROCESSING], [PAUSED_FOR_USER], [PAUSED_FOR_INPUT]) but says "possible reasons are", not
 * "the reasons are", and firmwares do add their own - an enum would turn a reason this code has
 * not seen into a line that does not decode at all, which is worse than a string it cannot
 * interpret. Compare against the constants when you need to branch.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
data class BusyRs(val reason: String) : GRS<BusyRs> {

    override fun encode(): String = "busy: $reason"

    companion object : GRSDecoder<BusyRs> {

        /** Busy with a lengthy command - homing, heat-up, auto levelling. */
        const val PROCESSING = "processing"

        /** Paused, awaiting an action by the user on the machine's own controller. */
        const val PAUSED_FOR_USER = "paused for user"

        /** Paused, awaiting input from the user on the machine's own controller. */
        const val PAUSED_FOR_INPUT = "paused for input"

        // The reason has to start with a non-space so that a bare `busy:` does not decode to an
        // empty reason, which would encode back to `busy: ` and not round-trip.
        private val BUSY_PATTERN = Pattern.compile(
            "^busy[ \t]*:[ \t]*(\\S.*)$",
            Pattern.CASE_INSENSITIVE
        )

        override fun match(line: String): Boolean = BUSY_PATTERN.matcher(line.trim()).matches()

        override fun decodeParams(line: String): BusyRs {
            val matcher = BUSY_PATTERN.matcher(line.trim())
            require(matcher.matches()) { "not a busy reply: $line" }
            return BusyRs(matcher.group(1))
        }
    }
}
