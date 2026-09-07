package org.qw3rtrun.p3d.g.code.core.token

import java.math.BigDecimal

// ASCII character classes, spelled out rather than taken from Char.isLetter() / isDigit() /
// isWhitespace(), which accept whole Unicode categories. The wire format is 7-bit ASCII
// (spec 1.1), so a non-ASCII character outside a comment or a quoted string is a lexical error
// (spec 9), not a word - and these four lines transliterate to C, Rust and JS unchanged.
private fun isDigit(c: Char) = c >= '0' && c <= '9'
private fun isUpper(c: Char) = c >= 'A' && c <= 'Z'
private fun isLower(c: Char) = c >= 'a' && c <= 'z'
private fun isLetter(c: Char) = isUpper(c) || isLower(c)

/**
 * The separators of spec 2.1 (space, tab) and the terminator characters of spec 1.2 (LF, CR) -
 * exactly the four [GTokenizerIterator.space] handles, and nothing else. `Char.isWhitespace()` also
 * accepted VT, FF, the file separators, NBSP and LINE SEPARATOR, all of which fell through to a dead
 * `else` inside `space()`; they now reach the top-level `else` of `next()` and become `GUnknown`
 * along the same path as every other unrecognised character.
 */
private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

class GTokenizer {

    fun parse(gcode: Iterator<Char>): Iterator<GToken> = GTokenizerIterator(gcode)

    // Sequence { ... } rather than Iterator.asSequence(): the latter is constrainOnce(), which made
    // the result consumable exactly once even when the source could be walked again (TODO 1.11).
    // Re-iterability is inherited from the source, so only the bare-Iterator overload is single-use.
    fun parse(gcode: Iterable<Char>): Sequence<GToken> = Sequence { GTokenizerIterator(gcode.iterator()) }
    fun parse(gcode: Sequence<Char>): Sequence<GToken> = Sequence { GTokenizerIterator(gcode.iterator()) }
    fun parse(gcode: CharSequence): Sequence<GToken> = Sequence { GTokenizerIterator(gcode.iterator()) }

    /**
     * Tokenizes lines whose terminators have already been stripped, as `readLines()` and
     * `lineSequence()` produce them, re-inserting [terminator] *between* them.
     *
     * The terminator is the caller's choice because the line source no longer carries it: a file
     * read on Windows and one read on Linux arrive here identically. Concatenating without it fused
     * consecutive commands into a single line (TODO 1.5).
     */
    fun parseLines(gcode: Sequence<String>, terminator: String = "\n"): Sequence<GToken> =
        Sequence { GTokenizerIterator(GLineCharIterator(gcode.iterator(), terminator)) }
}

/**
 * Concatenates already-split lines back into a character stream, putting [terminator] between
 * consecutive lines and never after the last one.
 */
private class GLineCharIterator(
    private val lines: Iterator<String>,
    private val terminator: String,
) : Iterator<Char> {

    /** The current line with its terminator re-attached, consumed character by character. */
    private var chunk: String = ""
    private var index = 0

    private fun fill() {
        while (index >= chunk.length && lines.hasNext()) {
            val line = lines.next()
            // The terminator separates lines, so the final one does not get one.
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

class GTokenizerIterator(private val chars: Iterator<Char>) : Iterator<GToken> {

    private var ch: Char? = null

    override fun hasNext() = ch != null || chars.hasNext()

    override fun next(): GToken {
        // Iterator.next() specifies NoSuchElementException. Without this guard the type depended on
        // the source overload - a String source raised StringIndexOutOfBoundsException (TODO 1.17).
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

    /**
     * Lexes one separator. Only the four characters of [isSpace] reach here, so the CR case is the
     * fall-through: there is no fifth possibility to guard against.
     */
    fun space(current: Char): GToken {
        ch = null
        if (current == ' ') return GSpace
        if (current == '\t') return GTab
        if (current == '\n') return GLineBreak()
        // spec 1.2: CR terminates a line only as the first half of CRLF.
        if (chars.hasNext()) {
            val next = chars.next()
            // CR and LF belong to one break token, both characters are consumed.
            if (next == '\n') return GLineBreak("\r\n")
            // A lone CR is not a break: keep the lookahead for the next token.
            ch = next
            return GUnknown('\r')
        }
        return GUnknown(current)
    }

    /**
     * Lexes a number per spec section 3.1: `[+|-] digits [ . digits ]` or `[+|-] . digits`, with at
     * least one digit somewhere and at most one decimal point.
     *
     * The lexeme is handed to the token verbatim, so a sign, leading zeros (`G01`) and a trailing
     * dot (`X1.`) survive `rawText()` unchanged.
     */
    fun number(start: Char): GToken {
        ch = null
        val raw = StringBuilder()
        var dots = 0
        var digits = 0
        var current: Char? = start

        if (current == '+' || current == '-') {
            raw.append(current)
            current = if (chars.hasNext()) chars.next() else null
            // A sign belongs to a number and to nothing else. If no number follows, the sign is a
            // lexical error and the character that followed it goes back into the lookahead.
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
        // Whatever stopped the number (null at end of input) is the next token's first character.
        ch = current

        val text = raw.toString()
        return when {
            // A lone `.`, a bare sign or more than one decimal point is not a number: keep the
            // lexeme as-is instead of letting BigDecimal throw out of the iterator.
            digits == 0 || dots > 1 -> GUnknown(text)
            dots == 1 -> GFloat(BigDecimal(text), text)
            // An integer that does not fit an Int is kept verbatim rather than silently truncated.
            //
            // spec 1.1: `toIntOrNull` and `BigDecimal(String)` are both wider than the class that
            // guards them - they accept the whole Unicode Nd category, so `"\u0661".toIntOrNull()`
            // is 1. Only [isDigit] keeps a non-ASCII digit out of `text`; do not relax it on the
            // assumption that the conversion would reject one.
            else -> text.toIntOrNull()?.let { GInt(it, text) } ?: GUnknown(text)
        }
    }

    /**
     * Lexes a quoted string per spec section 3.4: `"` to the next unpaired `"`, where a doubled `""`
     * inside the string is one literal quote.
     *
     * Two texts are tracked because they differ: [text] is the decoded content the token carries,
     * [raw] is the exact lexeme. Only the lexeme can represent an unterminated string, which is a
     * lexical error (spec section 9) rather than a string that gains a closing quote it never had.
     */
    fun string(): GToken {
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
                // spec 3.4b: "" is an escaped quote, not the end of the string.
                raw.append(next)
                text.append('"')
                current = if (chars.hasNext()) chars.next() else null
            } else {
                // The quote closed the string; whatever followed it belongs to the next token.
                terminated = true
                current = next
                break
            }
        }
        // Whatever stopped the scan (null at end of input) is the next token's first character.
        ch = current

        return if (terminated) GQuotedString(text.toString()) else GUnknown(raw.toString())
    }

    /**
     * Lexes a brace expression, the same nested scan as [inlineComment] over `{` `}`. Unlike a
     * comment an expression keeps its delimiters in the token text, so there is one buffer, not two.
     */
    fun expression(start: Char): GToken {
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

    /**
     * Lexes a `;` comment, which runs to the end of the line. The break itself is a separate token,
     * so it is left in the lookahead rather than consumed.
     *
     * spec 1.2: a line ends at LF or at CRLF, so **both** characters of a CRLF belong to the
     * terminator and neither is comment content. The scan therefore stops at either, and [space]
     * decides what the character it stopped on means - `\r\n` is one `GLineBreak("\r\n")`, a lone
     * `\r` is `GUnknown`, exactly as outside a comment. The CR is still emitted, by the separator
     * rather than by the comment, so `;ab\r\n` round-trips byte for byte.
     */
    fun tailComment(): GComment {
        ch = null
        val text = StringBuilder()
        var current: Char? = if (chars.hasNext()) chars.next() else null

        while (current != null && current != '\n' && current != '\r') {
            text.append(current)
            current = if (chars.hasNext()) chars.next() else null
        }
        // The break that ended the comment (null at end of input) starts the next token.
        ch = current

        return GTailComment(text.toString())
    }

    /**
     * Lexes a parenthesised comment per spec section 2, tracking nesting so an inner `(` `)` pair
     * stays part of the text.
     *
     * Nothing is read past the closing delimiter, so the lookahead is written once on entry and
     * never inside the loop. Writing it in the loop is what made an unterminated comment re-emit its
     * last character as a second token (TODO 1.7); appending the closing paren before the nesting
     * counter reached zero is what put it inside the comment text (TODO 1.2). `expression()` is the
     * same scan over `{` `}` and is deliberately kept in the same shape.
     */
    fun inlineComment(start: Char): GToken {
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
            // The delimiters frame the comment; only the closing one drops the depth to zero, so
            // every character seen while still nested is content.
            if (depth > 0) text.append(current)
        }

        return if (depth == 0) GInlineComment(text.toString()) else GUnknown(raw.toString())
    }
}
