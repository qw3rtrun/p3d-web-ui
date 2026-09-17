package org.qw3rtrun.p3d.g.marlin.protocol

import org.qw3rtrun.p3d.g.code.dsl.GRs
import org.qw3rtrun.p3d.g.code.dsl.GRSDecoder
import java.util.regex.Pattern

/**
 * `rs` / `Resend` - a line was not received intact and the machine wants it again.
 *
 * The spec allows four spellings of the same request, and a host that understands only one of
 * them stalls the resend loop of spec 8.5 forever:
 *
 * ```
 * Resend: 123
 * Resend: N123
 * rs:123
 * rs N123
 * ```
 *
 * All four decode to the same [ResendRs]; [encode] normalises to the `Resend: <n>` form the
 * spec's own worked example uses, the way [AdvancedOkRs] normalises its field order.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
data class ResendRs(val lineNumber: Int) : GRs<ResendRs> {

    override fun encode(): String = "Resend: $lineNumber"

    companion object : GRSDecoder<ResendRs> {

        // `rs` or `Resend`, then an optional `:`, then an optional `N` marker which may itself be
        // followed by a `:`, then the number. The separators are optional throughout because all
        // four documented spellings differ only in which of them are present.
        private val RESEND_PATTERN = Pattern.compile(
            "^(?>rs|resend)[ \t]*:?[ \t]*(?>n[ \t]*:?[ \t]*)?([0-9]+)$",
            Pattern.CASE_INSENSITIVE
        )

        override fun match(line: String): Boolean {
            val matcher = RESEND_PATTERN.matcher(line.trim())
            if (!matcher.matches()) return false
            // spec 9: a digit run too wide for Int is malformed input off the wire, not a crash.
            return matcher.group(1).toIntOrNull() != null
        }

        override fun decodeParams(line: String): ResendRs {
            val matcher = RESEND_PATTERN.matcher(line.trim())
            require(matcher.matches()) { "not a resend request: $line" }
            val lineNumber = matcher.group(1).toIntOrNull()
            require(lineNumber != null) { "resend line number does not fit an Int: $line" }
            return ResendRs(lineNumber)
        }
    }
}
