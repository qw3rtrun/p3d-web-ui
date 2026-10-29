package org.qw3rtrun.p3d.g.protocol


/**
 * `busy: <reason>` - the machine cannot take commands from the serial interface right now.
 *
 * ```
 * busy: processing
 * busy: paused for user
 * ```
 *
 * **An interface, because the same notice has more than one spelling on the wire.** The RepRap
 * protocol describes the bare line, which is [SimpleBusyRs]; Marlin writes the identical notice
 * through its echo channel, because `host_keepalive()` goes out with `SERIAL_ECHO_MSG`, and that is
 * `marlin.event`'s `BusyNotice` - which is both a `BusyRs` and an `EchoRs`, because it really is
 * both. A host that only wants to know *that the machine is busy and why* matches on this type and
 * does not care which firmware it is talking to; one that is re-emitting a captured line matches on
 * the variant, which round-trips to its own spelling.
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
 * They live here rather than on either variant, because the reason is the same string whichever
 * channel carried it.
 *
 * ```
 * val busy = MarlinRsDecoder.decode("echo:busy: processing")
 * (busy as? BusyRs<*>)?.reason == BusyRs.PROCESSING   // true, whatever the spelling
 * ```
 *
 * @property reason the reason, as the machine wrote it
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface BusyRs<D : BusyRs<D>> : GEventRs<D> {

    val reason: String

    companion object {

        const val PROCESSING = "processing"

        const val PAUSED_FOR_USER = "paused for user"

        const val PAUSED_FOR_INPUT = "paused for input"
    }
}

/**
 * `busy: <reason>` as the RepRap base protocol writes it, with nothing in front of it.
 *
 * The canonical spelling, and the one [encode] produces. Marlin is not the only firmware and not
 * every one of them routes the notice through an echo channel, so this is what the protocol says
 * and it stays exactly that while `marlin.event`'s `BusyNotice` reads Marlin's own form.
 *
 * The reason has to start with a non-space, so that a bare `busy:` does not decode to an empty
 * reason, which would encode back to `busy: ` and not round-trip.
 *
 * ```
 * SimpleBusyRs.decode("busy: processing")   // SimpleBusyRs(BusyRs.PROCESSING)
 * SimpleBusyRs.match("busy:")               // false
 * ```
 *
 * @property reason the reason, as the machine wrote it
 */
data class SimpleBusyRs(override val reason: String) : BusyRs<SimpleBusyRs>, GProtoRs<SimpleBusyRs> {

    override fun encode(): String = "busy: $reason"

    companion object : GRsDecoder<SimpleBusyRs> {

        private const val PREFIX = "busy"


        /**
         * `busy: <reason>`, or null when the line is not one.
         *
         * The reason must not be empty - `busy:` alone says nothing a host can act on, and the
         * pattern this replaced required a non-blank first character for the same reason. It runs
         * to the end of the line and is taken verbatim, blanks inside included: `processing`,
         * `paused for user`, and whatever a fork invents next.
         */
        override fun decode(line: String): SimpleBusyRs? {
            val text = line.trim()
            if (!text.startsWithAscii(PREFIX)) return null

            var i = text.blanksEnd(PREFIX.length)
            if (i >= text.length || text[i] != ':') return null
            i = text.blanksEnd(i + 1)
            if (i >= text.length) return null

            return SimpleBusyRs(text.substring(i))
        }
    }
}
