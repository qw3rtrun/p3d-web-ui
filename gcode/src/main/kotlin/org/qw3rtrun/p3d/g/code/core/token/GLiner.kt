package org.qw3rtrun.p3d.g.code.core.token

/**
 * Groups a token stream into lines and classifies each one, per GCODE_spec.md section 5.
 *
 * The classification is read off the line's **elements** - the tokens that are neither separators
 * nor comments - so leading whitespace never changes the answer (spec 2.1) and a `*` that the lexer
 * put inside a comment or a string is not a checksum marker.
 */
class GLineIterator(private val tokens: Iterator<GToken>) : Iterator<GLine> {

    override fun hasNext(): Boolean = tokens.hasNext()

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
            val next = tokens.next()
            line.add(next)
            if (next is GLineBreak) break
        } while (tokens.hasNext())
        return line
    }

    /**
     * Drops a trailing line break by **testing for it**. Computing a bound as `size - 1` instead
     * assumed every line was terminated: an unterminated one silently lost its last token, and a
     * short one threw out of `subList` (TODO 1.3).
     */
    private fun stripTerminator(line: List<GToken>): List<GToken> =
        if (line.isNotEmpty() && line[line.size - 1] is GLineBreak) line.subList(0, line.size - 1) else line

    /** Indices, into [body], of the tokens that carry meaning - separators and comments excluded. */
    private fun elementIndices(body: List<GToken>): List<Int> {
        val indices = ArrayList<Int>()
        for (i in body.indices) if (body[i] is GElement) indices.add(i)
        return indices
    }

    /**
     * spec 2.2: the dialects are case-insensitive. Explicit ASCII comparison rather than
     * `equals(ignoreCase = true)`, which is locale-dependent - a Turkish locale changes `i`/`I`.
     */
    private fun isLetter(token: GToken, upper: Char, lower: Char): Boolean =
        token is GLetter && (token.letter == upper || token.letter == lower)

    /** Element position of the checksum marker, or -1. */
    private fun checksumAt(body: List<GToken>, elements: List<Int>): Int {
        for (k in elements.indices) if (body[elements[k]] == GChecksum) return k
        return -1
    }

    /** The integer following element [at], or null when the next element is not one, or is absent. */
    private fun numberAfter(body: List<GToken>, elements: List<Int>, at: Int): GInt? {
        if (at + 1 >= elements.size) return null
        val token = body[elements[at + 1]]
        return if (token is GInt) token else null
    }

    private fun parseLine(line: List<GToken>): GLine {
        val body = stripTerminator(line)
        val elements = elementIndices(body)

        // spec 5: a line of nothing but whitespace and/or comments is a no-op. It keeps its tokens
        // so that the line still reproduces its input.
        if (elements.isEmpty()) return GEmptyLine(line)

        // spec 5 and 7.1: the line number, if present, is the first field of the line.
        val numbered = isLetter(body[elements[0]], 'N', 'n')
        val star = checksumAt(body, elements)

        // spec 7.3: a line number and a checksum must both be present or both be absent.
        if (!numbered && star < 0) return GSimpleLine(line)
        if (!numbered) return GMissingLineNumber(line)
        if (star < 0) return GMissingChecksum(numberAfter(body, elements, 0), line)

        // spec 7.1 and 8.1: both markers are present, so both must be followed by an integer.
        val number = numberAfter(body, elements, 0) ?: return GMalformedLineNumber(line)
        val value = numberAfter(body, elements, star) ?: return GMalformedChecksum(number, line)

        val numberAt = elements[1]
        val valueAt = elements[star + 1]
        return GPacketLine(
            number,
            body.subList(numberAt + 1, elements[star]),
            GCheckSumValue(GChecksum, value),
            body.subList(valueAt + 1, body.size),
        )
    }
}

class GCommandParser {
    private fun parseLine(tokens: List<GToken>): GLine {
        val members = tokens.filter { it is GElement }.map { it as GElement }.iterator()
        if (!members.hasNext()) {
            return GCommandLine(emptyList(), tokens)
        }

        var cmds = listOf<GCommand>()
        val first = members.next()
        if (first is GIdentifier && isCommand(first, true)) {
            var cmd: GIdentifier = first
            var params = mutableListOf<GElement>()
            while (members.hasNext()) {
                val next = members.next()
                if (next is GIdentifier && isCommand(next, false)) {
                    cmds = cmds + GCommand(cmd, params)
                    cmd = next
                    params = mutableListOf<GElement>()
                } else {
                    params.add(next)
                }
            }
            return GCommandLine(cmds + GCommand(cmd, params), tokens)
        } else return GNotIdentifierError(first, tokens)
    }

    // spec 2.2, as in GLineIterator: explicit ASCII comparison, never `equals(ignoreCase = true)`.
    private fun isCommand(token: GToken?, includeT: Boolean = false) = token is GLetter
            && (token.letter == 'M' || token.letter == 'm'
            || token.letter == 'G' || token.letter == 'g'
            || (includeT && (token.letter == 'T' || token.letter == 't')))
}
