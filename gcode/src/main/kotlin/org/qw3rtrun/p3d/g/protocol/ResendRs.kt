package org.qw3rtrun.p3d.g.protocol


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
 * The shape read is `rs` or `Resend`, then an optional `:`, then an optional `N` marker which may
 * itself be followed by a `:`, then the number. The separators are optional throughout because all
 * four documented spellings differ only in which of them are present. Spec 9: a digit run too wide
 * for `Int` is malformed input off the wire, not a crash, so it does not match.
 *
 * ```
 * ResendRs.decode("rs N123")          // ResendRs(123)
 * ResendRs(123).encode()              // "Resend: 123"
 * ResendRs.match("rs:99999999999")    // false
 * ```
 *
 * @property lineNumber the line the machine wants resent, and every line after it
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
data class ResendRs(val lineNumber: Int) : GProtoRs<ResendRs> {

    override fun encode(): String = "Resend: $lineNumber"

    companion object : GRsDecoder<ResendRs> {

        override fun match(line: String): Boolean = scan(line) != null

        override fun decodeParams(line: String): ResendRs =
            requireNotNull(scan(line)) { "not a resend request: $line" }

        /**
         * `rs` or `resend`, then the line number, or null when the line is not a resend request.
         *
         * Everything between is optional and Marlin writes it every way: `rs:1`, `Resend: 1`,
         * `resend: N1`, `rs n : 1`. The `:` after the keyword and the whole `N` marker may each be
         * absent, which is why this is a walk rather than a split - and the keyword is matched
         * longest-first, since `resend` starts with no prefix of `rs`.
         *
         * A number too wide for an `Int` is absence, not a throw.
         */
        private fun scan(line: String): ResendRs? {
            val text = line.trim()
            var i = when {
                text.startsWithAscii("resend") -> 6
                text.startsWithAscii("rs") -> 2
                else -> return null
            }

            i = text.blanksEnd(i)
            if (i < text.length && text[i] == ':') i = text.blanksEnd(i + 1)

            if (i < text.length && (text[i] == 'n' || text[i] == 'N')) {
                i = text.blanksEnd(i + 1)
                if (i < text.length && text[i] == ':') i = text.blanksEnd(i + 1)
            }

            val end = text.digitsEnd(i)
            if (end == i || end != text.length) return null
            return ResendRs(text.substring(i, end).toIntOrNull() ?: return null)
        }
    }
}
