package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.BusyRs
import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.SimpleBusyRs
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.digitsEnd
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * The `echo:` family - Marlin's channel for anything it wants to say in words.
 *
 * `serial_echo_start()` writes the prefix, and everything from a settings dump to a busy notice to
 * a rejected command comes out under it. [UnknownCommand] and [BusyNotice] are split out because a
 * host has to act on them; everything else is [EchoMessage].
 *
 * ```
 * EchoRsDecoder.decode("echo:Unknown command: \"M9999\"")   // UnknownCommand("M9999")
 * EchoRsDecoder.decode("echo:busy: processing")             // BusyNotice("processing")
 * EchoRsDecoder.decode("echo:busy")                         // EchoMessage("busy") - no reason
 * ```
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface EchoRs<D : EchoRs<D>> : GEventRs<D>

/**
 * `echo:<text>` - a line of prose from the firmware.
 *
 * Deliberately matches none of the specialisations - [UnknownCommand], [InvalidExtruder],
 * [BusyNotice], [DebugLevelRs], [ColdExtrusionRs], [ColdExtrusionPrevented] - so they partition
 * the prefix between them and no line is claimed by two, the way `DebugRs` and `ActionRs`
 * partition `//`.
 *
 * ```
 * EchoMessage.decode("echo:busy")   // EchoMessage("busy")
 * EchoMessage("busy").encode()      // "echo:busy"
 * ```
 *
 * @property text the line after `echo:`, trimmed
 */
data class EchoMessage(val text: String) : EchoRs<EchoMessage> {

    override fun encode(): String = "echo:$text"

    companion object : GRsDecoder<EchoMessage> {

        /**
         * The general `echo:` line - everything on that channel that is not one of the six kinds
         * with a class of its own.
         *
         * The `echo:` test comes first and is cheap, so the six reads below happen only for a line
         * that really is an echo. They are kept for a **direct** call, which has no other way to
         * know; [EchoRsDecoder] answers the same question by trying the specific decoders first and
         * never reaching this one for a line they claim.
         */
        override fun decode(line: String): EchoMessage? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(PREFIX)) return null
            if (UnknownCommand.match(line)) return null
            if (BusyNotice.match(line)) return null
            if (DebugLevelRs.match(line)) return null
            if (ColdExtrusionRs.match(line)) return null
            if (ColdExtrusionPrevented.match(line)) return null
            if (InvalidExtruder.match(line)) return null
            return EchoMessage(text.substring(PREFIX.length).asciiTrim())
        }

        internal const val PREFIX = "echo:"
    }
}

/**
 * `echo:Unknown command: "<command>"` - the firmware did not recognise what it was sent.
 *
 * Worth its own class because it is the one `echo:` a host must not ignore: it means a command was
 * accepted by the wire protocol, acknowledged with an `ok`, and then did nothing at all.
 *
 * ```
 * UnknownCommand.decode("echo:Unknown command: \"M9999\"")   // UnknownCommand("M9999")
 * ```
 *
 * @property command the rejected command, as the firmware quoted it
 */
data class UnknownCommand(val command: String) : EchoRs<UnknownCommand> {

    override fun encode(): String = "echo:Unknown command: \"$command\""

    companion object : GRsDecoder<UnknownCommand> {

        private const val BODY = "Unknown command:"

        private const val QUOTE = '"'

        /**
         * `echo:Unknown command: "<command>"`, or null when the line is not one.
         *
         * **The command runs to the last quote, not the first.** `(.*)` was greedy, and that is the
         * right reading rather than an accident of the pattern: a firmware quoting something that
         * itself contains quotes keeps them, and what a host wants is the text that was rejected,
         * as it was rejected.
         *
         * Expressed here as "the line ends at a quote", which is the same rule the anchored pattern
         * stated with `"[ \t]*$` - the line is already trimmed, so the closing quote is the last
         * character or there is no closing quote at all.
         */
        override fun decode(line: String): UnknownCommand? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(EchoMessage.PREFIX)) return null

            var i = text.blanksEnd(EchoMessage.PREFIX.length)
            if (!text.startsWithAscii(BODY, i)) return null
            i = text.blanksEnd(i + BODY.length)

            if (i >= text.length || text[i] != QUOTE) return null
            val end = text.length - 1
            if (end <= i || text[end] != QUOTE) return null

            return UnknownCommand(text.substring(i + 1, end))
        }
    }
}

/**
 * `echo:<command> Invalid extruder <n>` - the command named a tool the machine does not have.
 *
 * The second of the two `echo:` lines a host must not ignore, beside [UnknownCommand] and for the
 * same reason: the command was accepted by the wire protocol, acknowledged with an `ok`, and then
 * did nothing. `M104 T5 S100` on a one-extruder machine sets no temperature and reports success.
 *
 * [command] is the code the firmware quoted back, which is the one that was rejected - `M104` for
 * `M104 T5 S100`. It is kept as written rather than parsed into a `GCommand`: this is the
 * firmware's own echo of what it read, and a host matching on it wants the text it sent.
 *
 * Captured live from MarlinSim (bugfix-2.1.x): `M104 T5 S100` answers
 * `echo:M104 Invalid extruder 5`, and `M104 T1 S205` answers the same with `1`.
 *
 * ```
 * InvalidExtruder.decode("echo:M104 Invalid extruder 5")   // InvalidExtruder("M104", 5)
 * InvalidExtruder("M104", 5).encode()                      // "echo:M104 Invalid extruder 5"
 * ```
 *
 * @property command the command the firmware quoted, as it wrote it
 * @property index the extruder the command asked for
 */
data class InvalidExtruder(val command: String, val index: Int) : EchoRs<InvalidExtruder> {

    override fun encode(): String = EchoMessage.PREFIX + command + BODY + index

    companion object : GRsDecoder<InvalidExtruder> {

        private const val BODY = " Invalid extruder "

        /**
         * The command and the extruder index, or null when the line is not one of these.
         *
         * The command is whatever sits between `echo:` and ` Invalid extruder `, so a firmware
         * that quotes more than the code still decodes; what is pinned is the message and the
         * number behind it, which is the part a host reads.
         */
        override fun decode(line: String): InvalidExtruder? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(EchoMessage.PREFIX)) return null

            val from = text.blanksEnd(EchoMessage.PREFIX.length)
            var at = from
            while (at < text.length && !text.startsWithAscii(BODY, at)) at++
            if (at >= text.length || at == from) return null

            val digits = at + BODY.length
            val end = text.digitsEnd(digits)
            if (end == digits || end != text.length) return null

            return InvalidExtruder(text.substring(from, at), text.substring(digits, end).toIntOrNull() ?: return null)
        }
    }
}

/**
 * `echo:busy: <reason>` - Marlin's keepalive, saying it is working and not hung.
 *
 * **Both a [BusyRs] and an [EchoRs], because it is both.** What it reports is the base protocol's
 * busy notice; how it arrives is Marlin's echo channel, since `host_keepalive()` goes out with
 * `SERIAL_ECHO_MSG`. A host that wants to know the machine is busy matches `BusyRs<*>` and is
 * answered whichever firmware is on the other end; one routing or re-emitting echo traffic matches
 * `EchoRs<*>` and gets this too. Neither view has to know the other exists.
 *
 * `SimpleBusyRs` is the bare `busy: processing` the RepRap protocol describes and is left exactly
 * as it is - it is what other firmwares send - so the two spellings stay separate values that each
 * round-trip to their own form rather than one re-encoding to a line its printer never writes.
 * [reason] is the same string on both, and the constants to compare it against are
 * [BusyRs.PROCESSING], [BusyRs.PAUSED_FOR_USER] and [BusyRs.PAUSED_FOR_INPUT], which live on the
 * interface for that reason.
 *
 * A live probe found the gap: on a real printer `echo:busy: processing` was arriving as
 * undifferentiated [EchoMessage] text, so a host watching for a typed busy reply saw nothing at all
 * while the machine homed.
 *
 * The reason has to start with a non-blank, exactly as in [SimpleBusyRs]: a bare `echo:busy:`
 * carries nothing to act on, and `echo:busy` with no colon at all is ordinary prose - both stay
 * [EchoMessage].
 *
 * ```
 * BusyNotice.decode("echo:busy: processing")        // BusyNotice("processing")
 * BusyNotice("processing").encode()                 // "echo:busy: processing"
 * BusyNotice.decode("echo:busy")                    // null
 * ```
 *
 * @property reason the reason, as the machine wrote it
 */
data class BusyNotice(override val reason: String) : BusyRs<BusyNotice>, EchoRs<BusyNotice> {

    override fun encode(): String = EchoMessage.PREFIX + BUSY + ": " + reason

    companion object : GRsDecoder<BusyNotice> {

        private const val BUSY = "busy"

        /** `echo:` then `busy`, a `:`, and a non-empty reason, or null when the line is not one. */
        override fun decode(line: String): BusyNotice? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(EchoMessage.PREFIX)) return null

            var i = text.blanksEnd(EchoMessage.PREFIX.length)
            if (!text.startsWithAscii(BUSY, i)) return null

            i = text.blanksEnd(i + BUSY.length)
            if (i >= text.length || text[i] != ':') return null

            i = text.blanksEnd(i + 1)
            if (i >= text.length) return null

            return BusyNotice(text.substring(i))
        }
    }
}

/**
 * Any `echo:` line, tried most specific first.
 *
 * The order is the partition: [EchoMessage] excludes what the other two claim, so the three never
 * answer to the same line and the registry above does not depend on which is asked first.
 *
 * ```
 * EchoRsDecoder.decode("echo:Unknown command: \"M9999\"")   // UnknownCommand("M9999")
 * EchoRsDecoder.decode("echo:busy: processing")             // BusyNotice("processing")
 * EchoRsDecoder.decode("echo:Print time: 0s")               // EchoMessage("Print time: 0s")
 * ```
 */
object EchoRsDecoder : GRsDecoder<EchoRs<*>> {

    /**
     * One pass down the list, most specific first and the general `echo:` last.
     *
     * It was a `when` over seven `match` calls followed by a `decodeParams` that read the claimed
     * line again - and each of those `match`es was itself a full read, so an `echo:` line the last
     * arm claimed had been read eight times by the time it became a value.
     */
    private val decoders: List<GRsDecoder<EchoRs<*>>> = listOf(
        UnknownCommand,
        InvalidExtruder,
        BusyNotice,
        DebugLevelRs,
        ColdExtrusionRs,
        ColdExtrusionPrevented,
        EchoMessage,
    )

    override fun decode(line: String): EchoRs<*>? = decoders.firstNotNullOfOrNull { it.decode(line) }
}
