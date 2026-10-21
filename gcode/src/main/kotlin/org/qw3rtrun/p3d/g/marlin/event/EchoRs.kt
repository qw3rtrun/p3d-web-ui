package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.BusyRs
import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.GRs
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.startsWithAscii
import java.util.regex.Pattern

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
 * Deliberately matches neither [UnknownCommand] nor [BusyNotice], so the three partition the prefix
 * between them and no line is claimed by two, the way `DebugRs` and `ActionRs` partition `//`.
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

        override fun match(line: String): Boolean =
            line.trim().startsWithAscii(PREFIX) &&
                !UnknownCommand.match(line) &&
                !BusyNotice.match(line)

        override fun decodeParams(line: String): EchoMessage {
            require(match(line)) { "not an echo line: $line" }
            return EchoMessage(line.trim().substring(PREFIX.length).trim())
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

        private val PATTERN = Pattern.compile(
            "^echo:[ \t]*Unknown command:[ \t]*\"(.*)\"[ \t]*$",
            Pattern.CASE_INSENSITIVE
        )

        override fun match(line: String): Boolean = PATTERN.matcher(line.trim()).matches()

        override fun decodeParams(line: String): UnknownCommand {
            val matcher = PATTERN.matcher(line.trim())
            require(matcher.matches()) { "not an unknown-command line: $line" }
            return UnknownCommand(matcher.group(1))
        }
    }
}

/**
 * `echo:busy: <reason>` - Marlin's keepalive, saying it is working and not hung.
 *
 * **The Marlin spelling of a base-protocol reply.** `BusyRs` in `protocol/` is the canonical one and
 * reads the bare `busy: processing` the RepRap protocol describes; Marlin writes the same notice
 * through its echo channel, because `host_keepalive()` goes out with `SERIAL_ECHO_MSG`, so the
 * prefix is there and `BusyRs` never fires against it. A live probe found this: on a real printer,
 * `echo:busy: processing` was arriving as undifferentiated [EchoMessage] text and a host watching
 * for a typed busy reply saw nothing at all while the machine homed.
 *
 * The two are kept apart rather than merged. `BusyRs` is what the protocol says and other firmwares
 * send; this is what Marlin sends, and it round-trips to its own spelling rather than re-encoding to
 * a line Marlin does not write. [reason] is the same string on both, so the constants to compare it
 * against are [BusyRs.PROCESSING], [BusyRs.PAUSED_FOR_USER] and [BusyRs.PAUSED_FOR_INPUT].
 *
 * The reason has to start with a non-blank, exactly as in `BusyRs`: a bare `echo:busy:` carries
 * nothing to act on, and `echo:busy` with no colon at all is ordinary prose - both stay
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
data class BusyNotice(val reason: String) : EchoRs<BusyNotice> {

    override fun encode(): String = EchoMessage.PREFIX + BUSY + ": " + reason

    companion object : GRsDecoder<BusyNotice> {

        private const val BUSY = "busy"

        override fun match(line: String): Boolean = scan(line) != null

        override fun decodeParams(line: String): BusyNotice =
            requireNotNull(scan(line)) { "not a busy notice: $line" }

        /** `echo:` then `busy`, a `:`, and a non-empty reason, or null when the line is not one. */
        private fun scan(line: String): BusyNotice? {
            val text = line.trim()
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

    override fun match(line: String): Boolean =
        UnknownCommand.match(line) || BusyNotice.match(line) || EchoMessage.match(line)

    override fun decodeParams(line: String): EchoRs<*> = when {
        UnknownCommand.match(line) -> UnknownCommand.decodeParams(line)
        BusyNotice.match(line) -> BusyNotice.decodeParams(line)
        else -> EchoMessage.decodeParams(line)
    }
}
