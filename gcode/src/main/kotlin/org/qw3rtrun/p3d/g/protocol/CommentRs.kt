package org.qw3rtrun.p3d.g.protocol

import java.util.regex.Pattern

/**
 * The `//` family - a line the machine may send at any time, not in answer to anything.
 *
 * Two kinds share the prefix, which is why they share an interface the way [OkRs] covers its own
 * two: [ActionRs] is `//action:<command>`, addressed to the host, and [DebugRs] is everything
 * else, addressed to a human. Decode the family with [CommentRsDecoder] when you do not care
 * which.
 *
 * ```
 * CommentRsDecoder.decode("//action:pause")   // ActionRs("pause")
 * CommentRsDecoder.decode("// x")             // DebugRs("x")
 * ```
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface CommentRs<D : CommentRs<D>> : GProtoRs<D>

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
 *
 * ```
 * DebugRs.decode("// hi")            // DebugRs("hi")
 * DebugRs.decode("//action:pause")   // null
 * DebugRs("").encode()               // "//"
 * ```
 *
 * @property text the line after `//`, trimmed
 */
data class DebugRs(val text: String) : CommentRs<DebugRs> {

    override fun encode(): String = if (text.isEmpty()) "//" else "// $text"

    companion object : GRsDecoder<DebugRs> {

        /**
         * The `//` test is cheap and comes first, so the [ActionRs] read below happens only for a
         * line that really is a `//` one - and those are rare on a link. It is kept because this
         * decoder is also called directly, and an action line is not a debug line whichever way it
         * is reached; [CommentRsDecoder] answers the same question by trying the specific one
         * first.
         */
        override fun decode(line: String): DebugRs? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("//")) return null
            if (ActionRs.match(line)) return null
            return DebugRs(trimmed.substring(2).trim())
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
 *
 * ```
 * ActionRs.decode("//action:out_of_filament T0")   // ActionRs("out_of_filament", "T0")
 * ActionRs("prompt_begin", "Continue?").encode()   // "//action:prompt_begin Continue?"
 * ```
 *
 * @property action the action's command word
 * @property argument the rest of the line after the command word, or null when there is none
 */
data class ActionRs(val action: String, val argument: String? = null) : CommentRs<ActionRs> {

    override fun encode(): String =
        if (argument == null) "//action:$action" else "//action:$action $argument"

    companion object : GRsDecoder<ActionRs> {

        private val ACTION_PATTERN = Pattern.compile(
            "^//[ \t]*action[ \t]*:[ \t]*(\\S+)(?>[ \t]+(.*))?$",
            Pattern.CASE_INSENSITIVE
        )

        override fun decode(line: String): ActionRs? {
            val matcher = ACTION_PATTERN.matcher(line.trim())
            if (!matcher.matches()) return null
            return ActionRs(matcher.group(1), matcher.group(2))
        }
    }
}

/**
 * Either kind of `//` line, tried most specific first.
 *
 * ```
 * CommentRsDecoder.decode("//action:pause")   // ActionRs("pause")
 * ```
 */
object CommentRsDecoder : GRsDecoder<CommentRs<*>> {

    /** Most specific first, so the general one never has to be asked to stand aside. */
    override fun decode(line: String): CommentRs<*>? =
        ActionRs.decode(line) ?: DebugRs.decode(line)
}
