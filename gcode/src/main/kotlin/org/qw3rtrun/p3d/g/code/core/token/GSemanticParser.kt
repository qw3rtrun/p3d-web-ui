package org.qw3rtrun.p3d.g.code.core.token

import org.qw3rtrun.p3d.g.code.core.checkSumCalculatorFor

/**
 * Turns a token stream into a stream of classified lines, per GCODE_spec.md section 5.
 *
 * Two steps, both here. Grouping: tokens are cut into lines at each line break. Classification: a
 * line's shape is read off **two token positions** - the first identifier, which is its first field
 * (spec 5; whitespace per 2.1 and comments per 6 are not fields, so it is not necessarily token 0),
 * and the last `*`, which spec 5 puts last. A `*` the lexer put inside a comment or a string is part
 * of that token and is never an identifier, so it cannot be mistaken for the marker.
 *
 * **It does not build words.** Everything the shape depends on is a token or a token index: the
 * line number is the integer behind the first `N`, the checksum field is the integer behind the last
 * `*`, and spec 8.3's covered range is the tokens between the two. That is also what makes the range
 * exact without a word to reason about - a space before the marker is a token inside the range, and
 * a space after it is a token outside it, with nothing to absorb either. Words are the command
 * layer's, and [GCommandParser] builds them on demand.
 *
 * One instance consumes one token stream. [parseLine] is independent of that state and can be called
 * directly on a line's tokens.
 *
 * **A [GPacketLine] is only ever built for a line whose checksum has been verified** (spec section
 * 8). Verification happens here, ahead of the construction, so the type carries the invariant and
 * there is no `verify()` for a caller to forget: holding a `GPacketLine` means the line is intact.
 * A well-formed checksum field that does not match its bytes yields [GCheckSumFailedLine] instead.
 */
class GSemanticParser(private val source: Iterator<GToken>) : Iterator<GLine> {

    override fun hasNext(): Boolean = source.hasNext()

    override fun next(): GLine {
        // Iterator.next() specifies NoSuchElementException; without this the tokenizer's own
        // exception leaked out and its type depended on the character source (TODO 1.17).
        if (!hasNext()) throw NoSuchElementException("no more lines")
        return parseLine(nextLine())
    }

    /** Reads up to and including the next line break, or to the end of the stream. */
    private fun nextLine(): List<GToken> {
        val line = ArrayList<GToken>()
        do {
            val next = source.next()
            line.add(next)
            if (next is GLineBreak) break
        } while (source.hasNext())
        return line
    }

    fun parseLine(tokens: List<GToken>): GLine {
        // One pass over the tokens finds both positions the line shape is read from. No `break` on
        // the marker: spec 5 puts the field last, so the last one wins.
        var headIndex = -1
        var starIndex = -1
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token !is GIdentifier) continue
            if (headIndex < 0) headIndex = i
            if (token == GChecksum) starIndex = i
        }

        // spec 5: a line of nothing but whitespace and/or comments is a no-op - i.e. it has no
        // field at all. It keeps its tokens so that the line still reproduces its input.
        if (headIndex < 0) return GMeaninglessLine(tokens)

        // spec 7.1: `N` is followed by an integer. A bare `N`, or one followed by anything else, is
        // a malformed line number, and that is decided by the first field alone - reading a later
        // `N` as the line number is what made `M110 N7` unreadable (todo 05).
        var lineNumber: GInt? = null
        var numberIndex = -1
        val head = tokens[headIndex]
        if (head is GIdentifier && head.isLetter('N')) {
            numberIndex = valueIndex(tokens, headIndex)
            val value = if (numberIndex < 0) null else tokens[numberIndex]
            if (value !is GInt) return GMalformedLineNumber(tokens)
            lineNumber = value
        }

        // spec 7.3: a line number and a checksum must both be present or both be absent. Presence
        // of the marker is what pairs, not whether its value parsed - the pairing question is
        // answered before the field-syntax one, so `*` alone is unpaired rather than malformed.
        if (lineNumber == null && starIndex < 0) return GSimpleLine(tokens)
        if (lineNumber == null) return GMissingLineNumber(tokens)
        if (starIndex < 0) return GMissingChecksum(lineNumber, tokens)

        // spec 8.1: `*<unsigned-int>`. A marker with no integer after it - `N1*`, `*ABC`, `*10.5` -
        // is a *malformed* checksum, never a missing one: a host tells spec 7.3 (unpaired, reframe)
        // from spec 8.1 (garbled, resend the line - spec 8.5) by exactly this distinction.
        val checksumIndex = valueIndex(tokens, starIndex)
        val checksum = if (checksumIndex < 0) null else tokens[checksumIndex]
        if (checksum !is GInt) return GMalformedChecksum(lineNumber, tokens)

        // spec 8.1: the digit count selects the algorithm, and a width neither algorithm claims is
        // still a syntax answer - there is nothing to compare `*1234` against.
        val calculator = checkSumCalculatorFor(checksum.lexeme)
            ?: return GMalformedChecksum(lineNumber, tokens)

        // spec 8.3: from the `N`, inclusive, up to but not including the `*` - which over tokens is
        // exactly that and nothing more. Indentation before the `N` is outside; a space before the
        // marker is inside; the marker, its value and the terminator are outside.
        for (i in headIndex until starIndex) {
            val text = tokens[i].rawText()
            for (j in 0 until text.length) calculator.add(text[j])
        }

        // Compare `int`, never `GInt` equality: `GInt` is a data class whose `equals` includes the
        // lexeme, so a recomputed GInt(57, "57") is != a carried GInt(57, "057"). That would fail
        // every zero-padded line, and a CRC is zero-padded by definition (spec 8.4).
        val expected = calculator.get()
        if (expected.int != checksum.int) {
            return GCheckSumFailedLine(lineNumber, expected, checksum, tokens)
        }

        return GPacketLine(
            lineNumber,
            checksum,
            // From past the line number's *value* to the marker. Not from `headIndex + 1`, which
            // would leave the number itself in the body, and not a copy: a slice of the tokens
            // already in hand, so `body` and `raw` cannot drift apart.
            tokens.subList(numberIndex + 1, starIndex),
            tokens,
        )
    }

    /**
     * The index of the value behind the identifier at [from], or -1 when there is none.
     *
     * spec 2.1: whitespace may separate an identifier from its value, so `N 1` and `* 12` assemble
     * across it. Only whitespace is skipped - a comment between the two ends the field, and so does
     * another identifier, which is what makes `N*` a bare `N` rather than an `N` carrying a `*`.
     */
    private fun valueIndex(tokens: List<GToken>, from: Int): Int {
        var i = from + 1
        while (i < tokens.size && tokens[i] is GWhitespace) i++
        if (i < tokens.size && tokens[i] is GValue) return i
        return -1
    }
}
