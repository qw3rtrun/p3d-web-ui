package org.qw3rtrun.p3d.g.code.core.token

class GSemanticParser {

    fun parseLine(tokens: List<GToken>): GLine {
        val semantic = semantic(tokens)

        // spec 5: a line of nothing but whitespace and/or comments is a no-op. It keeps its tokens
        // so that the line still reproduces its input.
        if (semantic.none { it is GWord }) return GMeaninglessLine(semantic)

        // spec 5 and 7.1: the line number, if present, is the first field of the line.
        val first = semantic[0]
        val lineNumber = when (first) {
            is GParameterWord<*> if first.isLetter('N') && first.value !is GInt -> return GMalformedLineNumber(semantic)
            is GParameterWord<*> if first.isLetter('N') && first.value is GInt -> first.value
            is GWord if first.isLetter('N') -> return GMalformedLineNumber(semantic)
            else -> null
        }

        val numbered = lineNumber != null

        val starIndex = semantic.indexOfLast { it is GParameterWord<*> && it.isLetter('*') }
        val star = if (starIndex > 0) semantic[starIndex] else null
        val checked = star != null

        // spec 7.3: a line number and a checksum must both be present or both be absent.
        if (!numbered && !checked) return GSimpleLine(semantic)
        if (!numbered) return GMissingLineNumber(semantic)
        if (!checked) return GMissingChecksum(lineNumber, semantic)

        // spec 7.1 and 8.1: both markers are present, so both must be followed by an integer.
        if (star !is GParameterWord<*>) return GMalformedChecksum(lineNumber, semantic)
        if (star.value !is GInt) return GMalformedChecksum(lineNumber, semantic)


        return GPacketLine(
            lineNumber,
            semantic.subList(1, starIndex),
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