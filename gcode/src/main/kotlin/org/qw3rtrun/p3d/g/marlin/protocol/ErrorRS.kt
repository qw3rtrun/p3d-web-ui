package org.qw3rtrun.p3d.g.marlin.protocol

import org.qw3rtrun.p3d.g.code.dsl.GRS
import org.qw3rtrun.p3d.g.code.dsl.GRSDecoder
import java.util.regex.Pattern

/** The three prefixes an error reply is written with, and the lexeme each one writes. */
enum class ErrorPrefix(val lexeme: String) {
    /** `!!` - the original two-character error prefix. */
    BANGS("!!"),

    /** `Error:` - what Marlin and RepRapFirmware send. */
    ERROR("Error:"),

    /** `fatal:` - Repetier Firmware. */
    FATAL("fatal:"),
}

/**
 * `!!` / `Error:` / `fatal:` - something went wrong, with the machine's own words for it.
 *
 * ```
 * Error:checksum mismatch, Last Line: 66555
 * !! hardware fault
 * ```
 *
 * **The message is kept as text and is not classified here.** The spec sorts errors into
 * recoverable communication errors (followed immediately by a [ResendRs]), non-fatal errors such
 * as `Unknown command` and the SD card family, and everything else - which it says to treat as a
 * hardware fault that shuts the machine down. That is a decision with real consequences either
 * way, it is made on prose the spec only gives as examples, and it belongs to the host and not to
 * a decoder. What this class does promise is [lastLine], because the resend protocol of spec 8.5
 * is addressed by line number and the number is right there in the text.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
data class ErrorRs(val prefix: ErrorPrefix, val message: String) : GRS<ErrorRs> {

    /**
     * The line number in a `Last Line: <n>` message, or null when the message does not name one.
     *
     * Only the `Last Line:` spelling is read. `expected line <n1> got <n2>` names two numbers with
     * no marker saying which is which, so reading it would be a guess.
     */
    val lastLine: Int?
        get() {
            val matcher = LAST_LINE_PATTERN.matcher(message)
            if (!matcher.find()) return null
            // spec 9: a number too wide for Int is malformed input, so the field reads as absent.
            return matcher.group(1).toIntOrNull()
        }

    override fun encode(): String = when (prefix) {
        // `!!` is a two-character prefix and takes a space, the way `//` does. `Error:` and
        // `fatal:` end in their own separator, and the spec's example has nothing after it.
        ErrorPrefix.BANGS -> if (message.isEmpty()) prefix.lexeme else prefix.lexeme + " " + message
        else -> prefix.lexeme + message
    }

    companion object : GRSDecoder<ErrorRs> {

        private val LAST_LINE_PATTERN = Pattern.compile(
            "last line[ \t]*:[ \t]*([0-9]+)",
            Pattern.CASE_INSENSITIVE
        )

        override fun match(line: String): Boolean = prefixOf(line.trim()) != null

        override fun decodeParams(line: String): ErrorRs {
            val trimmed = line.trim()
            val prefix = prefixOf(trimmed)
            require(prefix != null) { "not an error reply: $line" }
            return ErrorRs(prefix, trimmed.substring(prefix.lexeme.length).trim())
        }

        private fun prefixOf(trimmed: String): ErrorPrefix? = ErrorPrefix.entries.firstOrNull {
            trimmed.startsWith(it.lexeme, ignoreCase = true)
        }
    }
}
