package org.qw3rtrun.p3d.g.code.core.token

import java.math.BigDecimal

private fun isDigit(c: Char) = c >= '0' && c <= '9'
private fun isUpper(c: Char) = c >= 'A' && c <= 'Z'
private fun isLower(c: Char) = c >= 'a' && c <= 'z'
private fun isLetter(c: Char) = isUpper(c) || isLower(c)

private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

/**
 * The lexer: characters in, tokens out, per GCODE_spec.md sections 1 to 3.
 *
 * **An object, not a class.** It has no state - one instance was interchangeable with another and
 * every caller built its own for nothing.
 *
 * Use it to tokenize a whole text ([parse]) or a stream of already-split lines ([parseLines]).
 * Every overload yields tokens whose `rawText()` concatenates back to the input byte for byte.
 *
 * There is no `Iterable<Char>` overload: nothing called it, and a caller holding one writes
 * `parse(it.asSequence())` or hands over its iterator.
 *
 * ```
 * GTokenizer.parse("G1 X10").toList()   // [GLetter('G'), GInt(1), GSpace, GLetter('X'), GInt(10)]
 * ```
 */
object GTokenizer {

    /**
     * Tokenizes a bare character iterator. Single-use, because the source is.
     *
     * ```
     * GTokenizer.parse("G28".iterator()).next()   // GLetter('G')
     * ```
     *
     * @param gcode the characters to lex, consumed as the tokens are read
     * @return an iterator of tokens over [gcode]
     */
    fun parse(gcode: Iterator<Char>): Iterator<GToken> = GTokenizerIterator(gcode)

    /**
     * Tokenizes a character sequence.
     *
     * Built with `Sequence { ... }` rather than `Iterator.asSequence()`: the latter is
     * `constrainOnce()`, which made the result consumable exactly once even when the source could be
     * walked again (TODO 1.11). Re-iterability is inherited from the source, so only the
     * bare-`Iterator` overload is single-use.
     *
     * ```
     * GTokenizer.parse("M105".asSequence()).toList()   // [GLetter('M'), GInt(105)]
     * ```
     *
     * @param gcode the characters to lex
     * @return a sequence of tokens, re-iterable whenever [gcode] is
     */
    fun parse(gcode: Sequence<Char>): Sequence<GToken> = Sequence { GTokenizerIterator(gcode.iterator()) }

    /**
     * Tokenizes a text. Re-iterable: each pass builds its own tokenizer over the same text, so the
     * sequence is not `constrainOnce` (TODO 1.11).
     *
     * ```
     * GTokenizer.parse("G1 X.5").toList()   // [GLetter('G'), GInt(1), GSpace, GLetter('X'), GFloat(".5")]
     * ```
     *
     * @param gcode the text to lex
     * @return a re-iterable sequence of tokens over [gcode]
     */
    fun parse(gcode: CharSequence): Sequence<GToken> = Sequence { GTokenizerIterator(gcode.iterator()) }

    /**
     * Tokenizes lines whose terminators have already been stripped, as `readLines()` and
     * `lineSequence()` produce them, re-inserting [terminator] *between* them.
     *
     * The terminator is the caller's choice because the line source no longer carries it: a file
     * read on Windows and one read on Linux arrive here identically. Concatenating without it fused
     * consecutive commands into a single line (TODO 1.5).
     *
     * ```
     * GTokenizer.parseLines(sequenceOf("G28", "M105")).toList()
     * // [GLetter('G'), GInt(28), GLineBreak("\n"), GLetter('M'), GInt(105)]
     * ```
     *
     * @param gcode the lines, without terminators
     * @param terminator what to put between consecutive lines, never after the last; `"\n"` by default
     * @return a sequence of tokens over the joined lines
     */
    fun parseLines(gcode: Sequence<String>, terminator: String = "\n"): Sequence<GToken> =
        Sequence { GTokenizerIterator(GLineCharIterator(gcode.iterator(), terminator)) }
}

/**
 * Concatenates already-split lines back into a character stream, putting [terminator] between
 * consecutive lines and never after the last one.
 *
 * It holds the current line with its terminator re-attached - or without one, for the final line -
 * and hands it out character by character, pulling the next line only once that one is spent.
 *
 * ```
 * GLineCharIterator(listOf("G1", "G2").iterator(), "\n")   // 'G', '1', '\n', 'G', '2'
 * ```
 */
private class GLineCharIterator(
    private val lines: Iterator<String>,
    private val terminator: String,
) : Iterator<Char> {

    private var chunk: String = ""
    private var index = 0

    private fun fill() {
        while (index >= chunk.length && lines.hasNext()) {
            val line = lines.next()
            chunk = if (lines.hasNext()) line + terminator else line
            index = 0
        }
    }

    override fun hasNext(): Boolean {
        fill()
        return index < chunk.length
    }

    override fun next(): Char {
        if (!hasNext()) throw NoSuchElementException("no more characters")
        return chunk[index++]
    }
}

/**
 * The state machine itself. **Internal, and its scanners private**: which characters `number()` or
 * `string()` consumes is how this lexer is built, not what it promises, and a port re-deciding that
 * should not be breaking a contract. [GTokenizer] is the surface.
 *
 * ```
 * GTokenizerIterator("X-5".iterator()).next()   // GLetter('X')
 * ```
 *
 * **Character classes are ASCII, spelled out** rather than taken from `Char.isLetter()` /
 * `isDigit()` / `isWhitespace()`, which accept whole Unicode categories. The wire format is 7-bit
 * ASCII (spec 1.1), so a non-ASCII character outside a comment or a quoted string is a lexical error
 * (spec 9), not a word - and the four predicates transliterate to C, Rust and JS unchanged. The
 * separator class is exactly spec 2.1's space and tab plus spec 1.2's LF and CR, and nothing else:
 * `Char.isWhitespace()` also accepted VT, FF, the file separators, NBSP and LINE SEPARATOR, all of
 * which fell through to a dead `else` inside the separator scanner; they now reach the top-level
 * `else` of [next] and become `GUnknown` along the same path as every other unrecognised character.
 * The digit predicate is written out again in `GFields.kt`, `GWords.kt` and `CheckSums.kt`, and that
 * is deliberate rather than an oversight waiting to be tidied: a shared one-line predicate would be a
 * cross-file dependency between four layers to save three tokens each, and a port that takes one
 * file takes its predicate with it. Do not merge them.
 *
 * **One field of lookahead**, written by each scanner exactly once, on the way out, with whatever
 * character stopped it - `null` at end of input - which is then the next token's first character.
 *
 * **Separators.** Only the four separator characters reach the separator scanner, so the CR case is
 * its fall-through and there is no fifth possibility to guard against. Spec 1.2: CR terminates a line
 * only as the first half of CRLF, so `\r\n` is one `GLineBreak("\r\n")` with both characters
 * consumed, while a lone CR is `GUnknown('\r')` and the character after it is kept as the lookahead.
 *
 * **Numbers**, per spec 3.1: `[+|-] digits [ . digits ]` or `[+|-] . digits`, with at least one digit
 * somewhere and at most one decimal point. The lexeme is handed to the token verbatim, so a sign,
 * leading zeros (`G01`) and a trailing dot (`X1.`) survive `rawText()` unchanged. A sign belongs to a
 * number and to nothing else: if no digit or dot follows it, the sign is a lexical error and the
 * character that followed goes back into the lookahead. A lone `.`, a bare sign or more than one
 * decimal point is not a number and is kept as-is in a `GUnknown`, instead of letting `BigDecimal`
 * throw out of the iterator; an integer that does not fit an `Int` is likewise kept verbatim rather
 * than silently truncated. Spec 1.1: `toIntOrNull` and `BigDecimal(String)` are both wider than the
 * class that guards them - they accept the whole Unicode Nd category, so `"١".toIntOrNull()` is
 * 1. Only the ASCII digit predicate keeps a non-ASCII digit out of the lexeme; do not relax it on the
 * assumption that the conversion would reject one.
 *
 * **Quoted strings**, per spec 3.4: `"` to the next unpaired `"`, where a doubled `""` inside the
 * string is one literal quote (spec 3.4b) rather than the end of it. Two texts are tracked because
 * they differ: the decoded content the token carries, and the exact lexeme. Only the lexeme can
 * represent an unterminated string, which is a lexical error (spec 9) rather than a string that
 * gains a closing quote it never had. Whatever follows the closing quote belongs to the next token.
 *
 * **Brace expressions** are the same nested scan as parenthesised comments, over `{` `}`. Unlike a
 * comment an expression keeps its delimiters in the token text, so there is one buffer, not two.
 *
 * **`;` comments** run to the end of the line. The break itself is a separate token, so it is left
 * in the lookahead rather than consumed. Spec 1.2: a line ends at LF or at CRLF, so **both**
 * characters of a CRLF belong to the terminator and neither is comment content. The scan therefore
 * stops at either, and the separator scanner decides what the character it stopped on means - `\r\n`
 * is one `GLineBreak("\r\n")`, a lone `\r` is `GUnknown`, exactly as outside a comment. The CR is
 * still emitted, by the separator rather than by the comment, so `;ab\r\n` round-trips byte for byte.
 *
 * **Parenthesised comments**, per spec section 2, track nesting so an inner `(` `)` pair stays part
 * of the text. Nothing is read past the closing delimiter, so the lookahead is written once on entry
 * and never inside the loop. Writing it in the loop is what made an unterminated comment re-emit its
 * last character as a second token (TODO 1.7); appending the closing paren before the nesting counter
 * reached zero is what put it inside the comment text (TODO 1.2). The delimiters frame the comment,
 * and only the closing one drops the depth to zero, so every character seen while still nested is
 * content. The brace-expression scan is deliberately kept in the same shape.
 */
internal class GTokenizerIterator(private val chars: Iterator<Char>) : Iterator<GToken> {

    private var ch: Char? = null

    override fun hasNext() = ch != null || chars.hasNext()

    /**
     * Lexes and returns the next token.
     *
     * Past the end this throws `NoSuchElementException`, as `Iterator.next()` specifies. Without
     * that guard the exception type depended on the source overload - a `String` source raised
     * `StringIndexOutOfBoundsException` (TODO 1.17). Malformed input never throws; it is a
     * `GUnknown` carrying the offending characters.
     *
     * ```
     * val it = GTokenizerIterator("G1".iterator())
     * it.next()   // GLetter('G')
     * it.next()   // GInt(1)
     * ```
     *
     * @return the next token
     */
    override fun next(): GToken {
        if (!hasNext()) throw NoSuchElementException("no more tokens")
        ch = ch ?: chars.next()
        return when {
            isSpace(ch!!) -> space(ch!!)
            isLetter(ch!!) -> {
                val letter = GLetter(ch!!)
                ch = null
                letter
            }

            isDigit(ch!!) || ch == '.' || ch == '-' || ch == '+' -> number(ch!!)
            ch == '\"' -> string()
            ch == '{' -> expression('{')
            ch == ';' -> tailComment()
            ch == '(' -> inlineComment('(')
            ch == '*' -> {
                ch = null;
                GChecksum
            }

            else -> {
                val unknown = GUnknown(ch!!)
                ch = null
                unknown
            }
        }
    }

    private fun space(current: Char): GToken {
        ch = null
        if (current == ' ') return GSpace
        if (current == '\t') return GTab
        if (current == '\n') return GLineBreak()
        if (chars.hasNext()) {
            val next = chars.next()
            if (next == '\n') return GLineBreak("\r\n")
            ch = next
            return GUnknown('\r')
        }
        return GUnknown(current)
    }

    private fun number(start: Char): GToken {
        ch = null
        val raw = StringBuilder()
        var dots = 0
        var digits = 0
        var current: Char? = start

        if (current == '+' || current == '-') {
            raw.append(current)
            current = if (chars.hasNext()) chars.next() else null
            if (current == null || !(isDigit(current) || current == '.')) {
                ch = current
                return GUnknown(raw.toString())
            }
        }

        while (current != null) {
            when {
                isDigit(current) -> digits++
                current == '.' -> dots++
                else -> break
            }
            raw.append(current)
            current = if (chars.hasNext()) chars.next() else null
        }
        ch = current

        val text = raw.toString()
        return when {
            digits == 0 || dots > 1 -> GUnknown(text)
            dots == 1 -> GFloat(BigDecimal(text), text)
            else -> text.toIntOrNull()?.let { GInt(it, text) } ?: GUnknown(text)
        }
    }

    private fun string(): GToken {
        ch = null
        val raw = StringBuilder().append('"')
        val text = StringBuilder()
        var terminated = false
        var current: Char? = if (chars.hasNext()) chars.next() else null

        while (current != null) {
            if (current != '"') {
                raw.append(current)
                text.append(current)
                current = if (chars.hasNext()) chars.next() else null
                continue
            }
            raw.append(current)
            val next = if (chars.hasNext()) chars.next() else null
            if (next == '"') {
                raw.append(next)
                text.append('"')
                current = if (chars.hasNext()) chars.next() else null
            } else {
                terminated = true
                current = next
                break
            }
        }
        ch = current

        return if (terminated) GQuotedString(text.toString()) else GUnknown(raw.toString())
    }

    private fun expression(start: Char): GToken {
        ch = null
        val raw = StringBuilder().append(start)
        var depth = 1

        while (depth > 0 && chars.hasNext()) {
            val current = chars.next()
            raw.append(current)
            when (current) {
                '{' -> depth++
                '}' -> depth--
            }
        }

        return if (depth == 0) GRawExpression(raw.toString()) else GUnknown(raw.toString())
    }

    private fun tailComment(): GComment {
        ch = null
        val text = StringBuilder()
        var current: Char? = if (chars.hasNext()) chars.next() else null

        while (current != null && current != '\n' && current != '\r') {
            text.append(current)
            current = if (chars.hasNext()) chars.next() else null
        }
        ch = current

        return GTailComment(text.toString())
    }

    private fun inlineComment(start: Char): GToken {
        ch = null
        val raw = StringBuilder().append(start)
        val text = StringBuilder()
        var depth = 1

        while (depth > 0 && chars.hasNext()) {
            val current = chars.next()
            raw.append(current)
            when (current) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth > 0) text.append(current)
        }

        return if (depth == 0) GInlineComment(text.toString()) else GUnknown(raw.toString())
    }
}
