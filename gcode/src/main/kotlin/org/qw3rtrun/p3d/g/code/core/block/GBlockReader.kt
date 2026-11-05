package org.qw3rtrun.p3d.g.code.core.block

import org.qw3rtrun.p3d.g.code.core.token.GChecksum
import org.qw3rtrun.p3d.g.code.core.token.GIdentifier
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GLineBreak
import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.GTokenizer

/**
 * Turns a token stream into a stream of classified blocks, per GCODE_spec.md section 5.
 *
 * Two steps, both here. Grouping: tokens are cut into blocks at each line break. Classification: a
 * block's shape is read off two token positions - the first identifier, which is its first field
 * (spec 5; whitespace per 2.1 and comments per 6 are not fields, so it is not necessarily token 0),
 * and the last `*`, which spec 5 puts last. A `*` inside a comment or string is part of that token
 * and never an identifier, so it cannot be mistaken for the marker.
 *
 * It does not build words: the line number is the integer behind the first `N`, the checksum is the
 * integer behind the last `*`, and spec 8.3's covered range is the tokens between the two - exact
 * without a word to reason about, since a space before the marker is inside the range and a space
 * after it is outside. Words are the command layer's.
 *
 * One instance consumes one token stream; classification itself is stateless.
 *
 * A [GFramedBlock] is only ever built for a block whose checksum has been verified here, ahead of
 * construction, so holding one means the block is intact - there is no `verify()` to forget. A
 * well-formed checksum field that does not match its bytes yields [GCheckSumFailedBlock] instead.
 *
 * [GBlockReader.blocks] is the usual way in; construct a reader directly to drive blocks by hand.
 *
 * ```
 * GBlockReader.blocks("N1 G28*18\n").first() is GFramedBlock   // true
 * val reader = GBlockReader(GTokenizer.parse("G28\nN1 G28*18\n").iterator())
 * reader.next()   // GPlainBlock
 * reader.next()   // GFramedBlock, number 1, checksum 18
 * ```
 *
 * How a block is classified, in the order the checks run:
 *
 * - One pass finds both positions; there is no `break` on the marker, so the last `*` wins (spec 5).
 * - Spec 5: no field at all is a no-op, [GMeaninglessBlock], keeping its tokens.
 * - Spec 7.1: `N` must be followed by an integer, decided by the first field alone - reading a later
 *   `N` as the line number is what made `M110 N7` unreadable (todo 05).
 * - Spec 7.3: a line number and a checksum must both be present or both absent - presence of the
 *   marker is what pairs, not whether its value parsed, so a `*` alone is unpaired rather than
 *   malformed.
 * - Spec 8.1: `*<unsigned-int>`. No integer after the marker (`N1*`, `*ABC`, `*10.5`) is malformed,
 *   never missing - that distinction is how a host tells spec 7.3 (unpaired, reframe) from spec 8.1
 *   (garbled, resend - spec 8.5). The digit count selects the algorithm; a width neither claims is
 *   still a syntax answer, since there is nothing to compare `*1234` against.
 * - Spec 8.3: the covered bytes run from the `N`, inclusive, to just before the `*`.
 * - Recomputed and carried checksums compare as `int`, never as `GInt`: `GInt.equals` includes the
 *   lexeme, so `GInt(57, "57")` would not equal a zero-padded `GInt(57, "057")`, failing every
 *   zero-padded block (CRC is zero-padded by definition, spec 8.4).
 * - A verified block's `body` runs from past the line number's value to the marker, as a slice of
 *   the tokens already in hand, so `body` and `raw` cannot drift apart.
 */
class GBlockReader(private val source: Iterator<GToken>) : Iterator<GBlock> {

    override fun hasNext(): Boolean = source.hasNext()

    /**
     * Reads the tokens up to and including the next line break, or to the end of the stream, and
     * classifies them as one block. Throws `NoSuchElementException` past the end, per the iterator
     * contract - without this the tokenizer's own exception leaked out, its type depending on the
     * character source (TODO 1.17). Malformed blocks never throw; they are `GError` variants.
     *
     * @return the next block, carrying every token it was read from
     */
    override fun next(): GBlock {
        if (!hasNext()) throw NoSuchElementException("no more blocks")
        return parseBlock(nextBlock())
    }

    private fun nextBlock(): List<GToken> {
        val block = ArrayList<GToken>()
        do {
            val next = source.next()
            block.add(next)
            if (next is GLineBreak) break
        } while (source.hasNext())
        return block
    }

    private fun parseBlock(tokens: List<GToken>): GBlock {
        var headIndex = -1
        var starIndex = -1
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token !is GIdentifier) continue
            if (headIndex < 0) headIndex = i
            if (token == GChecksum) starIndex = i
        }

        if (headIndex < 0) return GMeaninglessBlock(tokens)

        var lineNumber: GInt? = null
        var numberIndex = -1
        val head = tokens[headIndex]
        if (head is GIdentifier && head.isChar('N')) {
            numberIndex = tokens.valueIndex(headIndex)
            val value = if (numberIndex < 0) null else tokens[numberIndex]
            if (value !is GInt) return GMalformedLineNumber(tokens)
            lineNumber = value
        }

        if (lineNumber == null && starIndex < 0) return GPlainBlock(tokens)
        if (lineNumber == null) return GMissingLineNumber(tokens)
        if (starIndex < 0) return GMissingChecksum(lineNumber, tokens)

        val checksumIndex = tokens.valueIndex(starIndex)
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
            return GCheckSumFailedBlock(lineNumber, expected, checksum, tokens)
        }

        return GFramedBlock(
            lineNumber,
            checksum,
            tokens.subList(numberIndex + 1, starIndex),
            tokens,
        )
    }

    companion object {

        /**
         * The whole read pipeline: text in, classified blocks out (spec 1-5, 7-8).
         *
         * Lives on the reader and not the lexer because it is the reader's output: a lexer offering
         * blocks would depend on the layer above it. Re-iterable, like `GTokenizer.parse` over the
         * same source: each pass builds its own tokenizer and reader, so the sequence is not
         * `constrainOnce`.
         *
         * ```
         * for (block in GBlockReader.blocks("G28\nN1 G28*18\n")) println(block::class.simpleName)
         * // GPlainBlock, GFramedBlock
         * ```
         *
         * @param gcode the text to read
         * @return a re-iterable sequence of classified blocks, each reproducing its own bytes
         */
        fun blocks(gcode: CharSequence): Sequence<GBlock> =
            Sequence { GBlockReader(GTokenizer.parse(gcode.iterator())) }
    }
}
