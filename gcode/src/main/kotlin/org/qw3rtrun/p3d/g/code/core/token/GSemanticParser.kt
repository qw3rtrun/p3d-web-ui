package org.qw3rtrun.p3d.g.code.core.token

class GSemanticParser {

    fun parseLine(tokens: List<GToken>): GLine {
        val semantic = semantic(tokens)

        // One pass over the elements gets both fields the line shape is read from: the first word
        // and the last `*` word.
        //
        // spec 5 and 7.1: the line number, if present, is the first *field* of the line. A field is
        // a word; spec 2.1 makes whitespace a separator and spec 6 leaves a comment out of the
        // field sequence, so the first field is not necessarily element 0 and has to be scanned
        // for. Reading semantic[0] positionally is what made ` N1 G28*18` report as unnumbered
        // (TODO 1.8, twice now).
        var head: GWord? = null
        var headIndex = -1
        var star: GWord? = null
        var starIndex = -1
        for (i in semantic.indices) {
            val e = semantic[i]
            if (e !is GWord) continue
            if (head == null) {
                head = e
                headIndex = i
            }
            // Any word whose identifier is the `*` token, not only one that carries a value: a
            // marker with nothing usable after it (`*`, `*ABC`) is a GFlagWord, and skipping those
            // reported a garbled checksum as an absent one. spec 5 puts the field last, so the
            // last marker wins and no `break` here.
            if (e.id == GChecksum) {
                star = e
                starIndex = i
            }
        }

        // spec 5: a line of nothing but whitespace and/or comments is a no-op - i.e. it has no
        // field at all. It keeps its tokens so that the line still reproduces its input.
        if (head == null) return GMeaninglessLine(semantic)

        // spec 7.1: `N` is followed by an integer. A flag word (`N` alone) or any other value is
        // a malformed line number, and that is decided by the head field alone.
        var lineNumber: GInt? = null
        if (head.isLetter('N')) {
            val value = if (head is GParameterWord<*>) head.value else null
            if (value !is GInt) return GMalformedLineNumber(semantic)
            lineNumber = value
        }

        // spec 7.3: a line number and a checksum must both be present or both be absent. Presence
        // of the marker is what pairs, not whether its value parsed - the pairing question is
        // answered before the field-syntax one, so `*` alone is unpaired rather than malformed.
        if (lineNumber == null && star == null) return GSimpleLine(semantic)
        if (lineNumber == null) return GMissingLineNumber(semantic)
        if (star == null) return GMissingChecksum(lineNumber, semantic)

        // spec 8.1: `*<unsigned-int>`. A marker with no integer after it - `N1*`, `*ABC`, `*10.5` -
        // is a *malformed* checksum, never a missing one: a host tells spec 7.3 (unpaired, reframe)
        // from spec 8.1 (garbled, resend the line - spec 8.5) by exactly this distinction.
        val checksum = if (star is GParameterWord<*>) star.value else null
        if (checksum !is GInt) return GMalformedChecksum(lineNumber, semantic)

        return GPacketLine(
            lineNumber,
            // From after the line-number field to the `*`, not from 1: with leading whitespace or a
            // comment the head field is not at index 0. `raw` below still holds the whole line.
            semantic.subList(headIndex + 1, starIndex),
            star as GParameterWord<GInt>,
            semantic
        )
    }

    private fun semantic(tokens: List<GToken>): List<GSemantic> {
        return SemanticIterator(tokens.iterator()).asSequence().toList()
    }

    class SemanticIterator(val body: Iterator<GToken>) : Iterator<GSemantic> {
        private val buffer = ArrayDeque<GToken>()

        override fun hasNext(): Boolean {
            return buffer.isNotEmpty() || body.hasNext()
        }

        override fun next(): GSemantic {
            if (!hasNext()) {
                throw NoSuchElementException("No more semantic elements")
            }
            val first = nextToken()!!
            if (first is GIdentifier) {
                val spaces = mutableListOf<GToken>()
                var following: GToken? = null
                while (hasNextToken()) {
                    val tok = nextToken()!!
                    if (tok is GWhitespace) {
                        spaces.add(tok)
                    } else {
                        following = tok
                        break
                    }
                }
                if (following is GValue) {
                    val rawList = ArrayList<GToken>(1 + spaces.size + 1)
                    rawList.add(first)
                    rawList.addAll(spaces)
                    rawList.add(following)
                    return GParameterWord(first, following, rawList)
                } else {
                    if (following != null) {
                        buffer.addFirst(following)
                    }
                    for (i in spaces.indices.reversed()) {
                        buffer.addFirst(spaces[i])
                    }
                    return GFlagWord(first, listOf(first))
                }
            } else {
                return GMeaningless(first)
            }
        }

        private fun hasNextToken(): Boolean {
            return buffer.isNotEmpty() || body.hasNext()
        }

        private fun nextToken(): GToken? {
            return if (buffer.isNotEmpty()) {
                buffer.removeFirst()
            } else if (body.hasNext()) {
                body.next()
            } else {
                null
            }
        }
    }
}

data class GParameterWord<out V : GValue>(
    override val id: GIdentifier,
    override val value: V,
    override val raw: List<GToken> = listOf(id, value),
) : GParameter<V>

data class GFlagWord(
    override val id: GIdentifier,
    override val raw: List<GToken> = listOf(id),
) : GFlag()