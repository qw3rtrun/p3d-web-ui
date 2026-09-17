package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.code.dsl.GRsDecoder
import org.qw3rtrun.p3d.g.code.dsl.GRs
import java.util.regex.Pattern

/**
 * The `echo:` family - Marlin's channel for anything it wants to say in words.
 *
 * `serial_echo_start()` writes the prefix, and everything from a settings dump to a busy notice to
 * a rejected command comes out under it. [UnknownCommand] is split out because a host has to act on
 * it; everything else is [EchoMessage].
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface EchoRs<D : EchoRs<D>> : GRs<D>

/**
 * `echo:<text>` - a line of prose from the firmware.
 *
 * Deliberately does **not** match [UnknownCommand], so the two partition the prefix between them
 * and no line is claimed by both, the way `DebugRs` and `ActionRs` partition `//`.
 */
data class EchoMessage(val text: String) : EchoRs<EchoMessage> {

    override fun encode(): String = "echo:$text"

    companion object : GRsDecoder<EchoMessage> {

        override fun match(line: String): Boolean =
            line.trim().startsWith(PREFIX, ignoreCase = true) && !UnknownCommand.match(line)

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

/** Either kind of `echo:` line, tried most specific first. */
object EchoRsDecoder : GRsDecoder<EchoRs<*>> {

    override fun match(line: String): Boolean = UnknownCommand.match(line) || EchoMessage.match(line)

    override fun decodeParams(line: String): EchoRs<*> =
        if (UnknownCommand.match(line)) UnknownCommand.decodeParams(line)
        else EchoMessage.decodeParams(line)
}
