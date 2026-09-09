package org.qw3rtrun.p3d.g.code.core.token

/**
 * Groups a token stream into lines, one line per line break, and hands each to [GSemanticParser]
 * for classification, per GCODE_spec.md section 5.
 *
 * Grouping only - this class does not look inside a line. Classification is read off the line's
 * **semantic elements**, so leading whitespace never changes the answer (spec 2.1) and a `*` the
 * lexer put inside a comment or a string is not a checksum marker; both rules live in
 * [GSemanticParser].
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

}
