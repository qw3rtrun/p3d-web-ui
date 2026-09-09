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
        return GSemanticParser().parseLine(nextLine())
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
        for (i in body.indices) if (body[i] is GValue) indices.add(i)
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

}

class GCommandParser {
//    private fun parseLine(tokens: List<GToken>): List<GCommand> {
//        val members = tokens.filter { it is GValue }.map { it as GValue }.iterator()
//        if (!members.hasNext()) {
//            return emptyList()
//        }
//
//        var cmds = listOf<GCommand>()
//        val first = members.next()
//        if (first is GIdentifier && isCommand(first, true)) {
//            var cmd: GIdentifier = first
//            var params = mutableListOf<GValue>()
//            while (members.hasNext()) {
//                val next = members.next()
//                if (next is GIdentifier && isCommand(next, false)) {
//                    cmds = cmds + GCommand(cmd, params)
//                    cmd = next
//                    params = mutableListOf<GValue>()
//                } else {
//                    params.add(next)
//                }
//            }
//            return cmds + GCommand(cmd, params)
//        } else throw GNotIdentifierError(first, tokens)
//    }

    // spec 2.2, as in GLineIterator: explicit ASCII comparison, never `equals(ignoreCase = true)`.
    private fun isCommand(token: GToken?, includeT: Boolean = false) = token is GLetter
            && (token.letter == 'M' || token.letter == 'm'
            || token.letter == 'G' || token.letter == 'g'
            || (includeT && (token.letter == 'T' || token.letter == 't')))
}
