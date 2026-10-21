package org.qw3rtrun.p3d.g.protocol


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
 * interpret. Compare against the constants when you need to branch:
 *
 * - `PROCESSING` - busy with a lengthy command: homing, heat-up, auto levelling.
 * - `PAUSED_FOR_USER` - paused, awaiting an action by the user on the machine's own controller.
 * - `PAUSED_FOR_INPUT` - paused, awaiting input from the user on the machine's own controller.
 *
 * The reason has to start with a non-space, so that a bare `busy:` does not decode to an empty
 * reason, which would encode back to `busy: ` and not round-trip.
 *
 * ```
 * BusyRs.decode("busy: processing")   // BusyRs(BusyRs.PROCESSING)
 * BusyRs.match("busy:")               // false
 * ```
 *
 * @property reason the reason, as the machine wrote it
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
data class BusyRs(val reason: String) : GProtoRs<BusyRs> {

    override fun encode(): String = "busy: $reason"

    companion object : GRsDecoder<BusyRs> {

        const val PROCESSING = "processing"

        const val PAUSED_FOR_USER = "paused for user"

        const val PAUSED_FOR_INPUT = "paused for input"

        private const val PREFIX = "busy"

        override fun match(line: String): Boolean = scan(line) != null

        override fun decodeParams(line: String): BusyRs =
            requireNotNull(scan(line)) { "not a busy reply: $line" }

        /**
         * `busy: <reason>`, or null when the line is not one.
         *
         * The reason must not be empty - `busy:` alone says nothing a host can act on, and the
         * pattern this replaced required a non-blank first character for the same reason. It runs
         * to the end of the line and is taken verbatim, blanks inside included: `processing`,
         * `paused for user`, and whatever a fork invents next.
         */
        private fun scan(line: String): BusyRs? {
            val text = line.trim()
            if (!text.startsWithAscii(PREFIX)) return null

            var i = text.blanksEnd(PREFIX.length)
            if (i >= text.length || text[i] != ':') return null
            i = text.blanksEnd(i + 1)
            if (i >= text.length) return null

            return BusyRs(text.substring(i))
        }
    }
}
