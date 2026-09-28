package org.qw3rtrun.p3d.g.code.core.block

import org.qw3rtrun.p3d.g.code.core.token.GChecksum
import org.qw3rtrun.p3d.g.code.core.token.GIdentifier
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GLineBreak
import org.qw3rtrun.p3d.g.code.core.token.GToken

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
 * layer's, and the DSL and `GRq.encode()` are what build them.
 *
 * One instance consumes one token stream. [parseLine] is independent of that state and can be called
 * directly on a line's tokens.
 *
 * **A [GPacketLine] is only ever built for a line whose checksum has been verified** (spec section
 * 8). Verification happens here, ahead of the construction, so the type carries the invariant and
 * there is no `verify()` for a caller to forget: holding a `GPacketLine` means the line is intact.
 * A well-formed checksum field that does not match its bytes yields [GCheckSumFailedLine] instead.
 *
 * `GTokenizer.lines(text)` is the usual way in; construct a liner directly to drive the lines by
 * hand, or when the tokens are already in hand.
 *
 * ```
 * val liner = GLiner(GTokenizer.parse("G28\nN1 G28*18\n").iterator())
 * liner.next()   // GSimpleLine
 * liner.next()   // GPacketLine, number 1, checksum 18
 * ```
 *
 * **How a line is classified**, in the order the checks run:
 *
 * - One pass over the tokens finds both positions the shape is read from. There is no `break` on the
 *   marker: spec 5 puts the field last, so the last `*` wins.
 * - Spec 5: a line of no field at all is a no-op, [GMeaninglessLine]. It keeps its tokens so the line
 *   still reproduces its input.
 * - Spec 7.1: `N` is followed by an integer. A bare `N`, or one followed by anything else, is a
 *   malformed line number, and that is decided by the **first field alone** - reading a later `N` as
 *   the line number is what made `M110 N7` unreadable (todo 05).
 * - Spec 7.3: a line number and a checksum must both be present or both be absent. **Presence of the
 *   marker** is what pairs, not whether its value parsed - the pairing question is answered before the
 *   field-syntax one, so a `*` alone is unpaired rather than malformed.
 * - Spec 8.1: `*<unsigned-int>`. A marker with no integer after it - `N1*`, `*ABC`, `*10.5` - is a
 *   *malformed* checksum, never a missing one: a host tells spec 7.3 (unpaired, reframe) from spec 8.1
 *   (garbled, resend the line - spec 8.5) by exactly this distinction. The digit count selects the
 *   algorithm, and a width neither algorithm claims is still a syntax answer - there is nothing to
 *   compare `*1234` against.
 * - Spec 8.3: the covered bytes run from the `N`, inclusive, up to but not including the `*` - which
 *   over tokens is exactly that and nothing more. Indentation before the `N` is outside; a space
 *   before the marker is inside; the marker, its value and the terminator are outside.
 * - The recomputed and carried checksums are compared as `int`, **never as `GInt`**: `GInt` is a data
 *   class whose `equals` includes the lexeme, so a recomputed `GInt(57, "57")` is not equal to a
 *   carried `GInt(57, "057")`. That would fail every zero-padded line, and a CRC is zero-padded by
 *   definition (spec 8.4).
 * - A verified line's `body` runs from past the line number's *value* to the marker - not from just
 *   past the `N`, which would leave the number itself in the body - and it is a slice of the tokens
 *   already in hand rather than a copy, so `body` and `raw` cannot drift apart.
 */
class GLiner(private val source: Iterator<GToken>) : Iterator<GLine> {

    override fun hasNext(): Boolean = source.hasNext()

    /**
     * Reads the tokens up to and including the next line break, or to the end of the stream, and
     * classifies them as one line.
     *
     * Past the end this throws `NoSuchElementException`, as `Iterator.next()` specifies; without that
     * guard the tokenizer's own exception leaked out and its type depended on the character source
     * (TODO 1.17). Malformed lines never throw: they are the `GError` variants.
     *
     * ```
     * GLiner(GTokenizer.parse("N1 G28*19\n").iterator()).next()   // GCheckSumFailedLine, computed 18
     * ```
     *
     * @return the next line, carrying every token it was read from
     */
    override fun next(): GLine {
        if (!hasNext()) throw NoSuchElementException("no more lines")
        return parseLine(nextLine())
    }

    private fun nextLine(): List<GToken> {
        val line = ArrayList<GToken>()
        do {
            val next = source.next()
            line.add(next)
            if (next is GLineBreak) break
        } while (source.hasNext())
        return line
    }

    private fun parseLine(tokens: List<GToken>): GLine {
        var headIndex = -1
        var starIndex = -1
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token !is GIdentifier) continue
            if (headIndex < 0) headIndex = i
            if (token == GChecksum) starIndex = i
        }

        if (headIndex < 0) return GMeaninglessLine(tokens)

        var lineNumber: GInt? = null
        var numberIndex = -1
        val head = tokens[headIndex]
        if (head is GIdentifier && head.isLetter('N')) {
            numberIndex = valueIndex(tokens, headIndex)
            val value = if (numberIndex < 0) null else tokens[numberIndex]
            if (value !is GInt) return GMalformedLineNumber(tokens)
            lineNumber = value
        }

        if (lineNumber == null && starIndex < 0) return GSimpleLine(tokens)
        if (lineNumber == null) return GMissingLineNumber(tokens)
        if (starIndex < 0) return GMissingChecksum(lineNumber, tokens)

        val checksumIndex = valueIndex(tokens, starIndex)
        val checksum = if (checksumIndex < 0) null else tokens[checksumIndex]
        if (checksum !is GInt) return GMalformedChecksum(lineNumber, tokens)

        val calculator = checkSumCalculatorFor(checksum.lexeme)
            ?: return GMalformedChecksum(lineNumber, tokens)

        for (i in headIndex until starIndex) {
            val text = tokens[i].rawText()
            for (j in 0 until text.length) calculator.add(text[j])
        }

        val expected = calculator.get()
        if (expected.int != checksum.int) {
            return GCheckSumFailedLine(lineNumber, expected, checksum, tokens)
        }

        return GPacketLine(
            lineNumber,
            checksum,
            tokens.subList(numberIndex + 1, starIndex),
            tokens,
        )
    }
}
