package org.qw3rtrun.p3d.g.marlin.protocol

import org.qw3rtrun.p3d.g.code.dsl.GRs
import org.qw3rtrun.p3d.g.code.dsl.GRsDecoder
import java.util.regex.Pattern

/**
 * The `//` family - a line the machine may send at any time, not in answer to anything.
 *
 * Two kinds share the prefix, which is why they share an interface the way [OkRs] covers its own
 * two: [ActionRs] is `//action:<command>`, addressed to the host, and [DebugRs] is everything
 * else, addressed to a human. Decode the family with [CommentRsDecoder] when you do not care
 * which.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface CommentRs<D : CommentRs<D>> : GRs<D>

/**
 * `// <text>` - debugging or other information on a line of its own.
 *
 * ```
 * // This is some debugging or other information on a line on its own.
 * ```
 *
 * Deliberately does **not** match `//action:` lines, so that [DebugRs] and [ActionRs] partition
 * the `//` prefix between them and no line is claimed by both. A host that only wants the text
 * should decode with [CommentRsDecoder] and read [ActionRs.action] too, rather than expect this
 * class to cover the whole prefix.
 */
data class DebugRs(val text: String) : CommentRs<DebugRs> {

    override fun encode(): String = if (text.isEmpty()) "//" else "// $text"

    companion object : GRsDecoder<DebugRs> {

        override fun match(line: String): Boolean =
            line.trim().startsWith("//") && !ActionRs.match(line)

        override fun decodeParams(line: String): DebugRs {
            require(match(line)) { "not a debug line: $line" }
            return DebugRs(line.trim().substring(2).trim())
        }
    }
}

/**
 * `//action:<command>` - an instruction to the *host*, not information about the machine.
 *
 * ```
 * //action:pause
 * //action:out_of_filament T0
 * //action:prompt_begin Continue?
 * ```
 *
 * The command word and its rest-of-line argument are kept apart because every documented action
 * that takes one takes exactly one: `out_of_filament` takes the extruder, the `prompt_*` family
 * takes the text to show. The command is not checked against the documented set - an unknown
 * action is a host that is older than the firmware, and the spec says hosts ignore what they do
 * not understand rather than treat it as a broken line.
 */
data class ActionRs(val action: String, val argument: String? = null) : CommentRs<ActionRs> {

    override fun encode(): String =
        if (argument == null) "//action:$action" else "//action:$action $argument"

    companion object : GRsDecoder<ActionRs> {

        private val ACTION_PATTERN = Pattern.compile(
            "^//[ \t]*action[ \t]*:[ \t]*(\\S+)(?>[ \t]+(.*))?$",
            Pattern.CASE_INSENSITIVE
        )

        override fun match(line: String): Boolean = ACTION_PATTERN.matcher(line.trim()).matches()

        override fun decodeParams(line: String): ActionRs {
            val matcher = ACTION_PATTERN.matcher(line.trim())
            require(matcher.matches()) { "not an action command: $line" }
            return ActionRs(matcher.group(1), matcher.group(2))
        }
    }
}

/** Either kind of `//` line, tried most specific first. */
object CommentRsDecoder : GRsDecoder<CommentRs<*>> {

    override fun match(line: String): Boolean = ActionRs.match(line) || DebugRs.match(line)

    override fun decodeParams(line: String): CommentRs<*> =
        if (ActionRs.match(line)) ActionRs.decodeParams(line) else DebugRs.decodeParams(line)
}
