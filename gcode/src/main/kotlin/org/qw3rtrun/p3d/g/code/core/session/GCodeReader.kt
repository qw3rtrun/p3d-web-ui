package org.qw3rtrun.p3d.g.code.core.session

import org.qw3rtrun.p3d.g.code.core.token.*

/** Whether a session demands that every transmitted line be numbered and checksummed (spec 7.3). */
enum class GNumbering {
    /**
     * A file, or Marlin's default over a link: a line may carry no number at all, and consumes none.
     * Spec 7.2 - "line numbers are optional, and are conventionally omitted for G-code stored in
     * files, where there is no lossy link to protect".
     */
    OPTIONAL,

    /**
     * A strict link: an unnumbered command line is refused. Marlin applies exactly this while
     * saving to SD, where a dropped line would corrupt the stored file.
     */
    REQUIRED,
}

/**
 * The session layer: line-number continuity (GCODE_spec.md section 7.2), `M110`, and the verdict a
 * host answers a bad line with (section 8.5).
 *
 * **This is the first stateful piece of the module, and the only one.** Everything below it is a
 * pure function from bytes to tokens to lines; a session has a counter. That boundary is deliberate
 * and worth keeping: the counter lives here, not in the tokenizer or the liner.
 *
 * It is also deliberately **free of I/O**. A reader is driven by a plain iterator of [GLine] and
 * returns a value per line; it never reads, writes, blocks or schedules. Sending the resend request
 * a [GRejected] describes is the transport layer's job, and so is the send window on the other side
 * of the link. That is what makes the session portable along with the rest of `code/core`.
 *
 * One instance is one session. [lastLine] is the last number accepted, which every message and every
 * resend point is computed from.
 */
class GCodeReader(val numbering: GNumbering = GNumbering.OPTIONAL, first: Int = 0) {

    /**
     * The last accepted line number. Starts at 0, so a session's first transmitted line is `N1` -
     * Marlin initialises `last_N` the same way, and it is why a session conventionally opens with
     * `M110 N0` rather than relying on the default.
     *
     * Only an accepted line moves it. A rejected one never does, which is what makes
     * [GRejected.resend] answerable, and a discarded duplicate does not either.
     */
    var lastLine: Int = first
        private set

    /** What to do with [line], per sections 7.2 and 8.5. */
    fun read(line: GLine): GReceipt {
        // spec 5: "a line number is only consumed by a line that is actually transmitted". A blank
        // or comment-only line is not one, so it is accepted, consumes nothing, and is exempt from
        // the numbering mode - there is nothing on it to number.
        if (line is GMeaninglessLine) return GAccepted(line, null)

        // spec 8.5 step 2: the checksum is judged first. It costs nothing to order it this way here
        // because the liner has already decided it - a GPacketLine is verified by construction
        // (todo 04) - and it matches RepRapFirmware, which validates at buffer-fill time before the
        // line number is looked at anywhere. Marlin checks the number first; both reject the line
        // and both ask for the same resend, so the only thing riding on the order is the message.
        if (line is GCheckSumFailedLine) {
            return GCorrupted(line, line.expected.int, line.received.int, lastLine)
        }

        // spec 9 then spec 7.3: a line that did not parse into a judgeable shape, and then the two
        // pairing faults. Order matters between these two only in that a malformed field is a
        // narrower statement than an unpaired one.
        if (line is GMalformedChecksum) return GMalformed(line, "checksum is not a number", lastLine)
        if (line is GMalformedLineNumber) return GMalformed(line, "line number is not a number", lastLine)
        if (line is GMissingChecksum) return GUnchecksummed(line, lastLine)
        if (line is GMissingLineNumber) return GUnnumbered(line, lastLine)

        // Everything that is not ordered carries no line number: spec 7.2 makes that legal in a
        // file and Marlin accepts it over a link too, so it is the mode's decision, not an error
        // by itself. It consumes no number either way.
        if (line !is GOrdered) {
            if (numbering == GNumbering.REQUIRED) return GUnnumbered(line, lastLine)
            return GAccepted(line, null)
        }

        val number = line.number.int

        // spec 7.2: an M110 line sets the counter and is exempt from continuity, because
        // resynchronising is the one thing a host does *because* the sequence is already broken.
        val reset = resetValue(line)
        if (reset != null) {
            lastLine = reset
            return GAccepted(line, number)
        }

        if (number == lastLine + 1) {
            lastLine = number
            return GAccepted(line, number)
        }

        // spec 7.2: a repeat of the last line, or the one before it, is discarded in silence rather
        // than refused. The host had already retransmitted when the resend request reached it;
        // answering the second copy with another request would not converge.
        if (number == lastLine || number == lastLine - 1) return GDuplicate(line, number, lastLine)

        return GOutOfSequence(line, number, lastLine)
    }

    /**
     * The counter value an `M110` line asks for, or null when [line] is not one.
     *
     * Spec 7.2: the value is `M110`'s **`N` parameter**, not the line's own number, so `N1 M110 N7`
     * leaves the counter at 7. With no parameter it falls back to the line number, as Marlin does.
     *
     * **Read off the line's head, not the raw text.** Marlin decides a line is an `M110` with
     * `strstr(command, "M110")`, which means `M117 M110` resets its counter - a consequence of
     * scanning text rather than an intended rule. Copying it would let a status message silently
     * resynchronise the session, so this diverges knowingly.
     *
     * It reads tokens directly rather than going through a decoder, and that is a layering
     * decision: `SetGetLineNumber` is the typed `M110` and `MarlinCommands.decode` would return it,
     * but the session belongs to `code/core` and must not depend on the Marlin command registry -
     * the dependency runs the other way, and the KDoc above claims this layer ports with the rest of
     * the core. The head rule is `headWord`'s, so this cannot disagree with a decoder about what a
     * command word is, and the `N` scan is the same `valueIndex` pairing every other reader uses.
     */
    private fun resetValue(line: GOrdered): Int? {
        val body = line.body
        val head = headWord(body) ?: return null
        if (!head.id.isLetter('M')) return null
        if (head.value.lexeme != "110") return null

        // spec 7.1: only the *first* field of a line is a line number, so the `N` this looks for is
        // M110's argument - which is why the search starts past the head rather than at the line.
        var i = headEnd(body)
        while (i < body.size) {
            val token = body[i]
            if (token is GIdentifier && token.isLetter('N')) {
                val j = valueIndex(body, i)
                val value = if (j < 0) null else body[j]
                if (value is GInt) return value.int
            }
            i++
        }
        return line.number.int
    }
}
