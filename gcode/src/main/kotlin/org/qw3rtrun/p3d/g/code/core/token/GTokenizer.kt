package org.qw3rtrun.p3d.g.code.core.token

import java.math.BigDecimal
import java.util.stream.Stream
import kotlin.streams.asSequence
import kotlin.streams.asStream

class GTokenizer {

    fun parse(gcode: Iterator<Char>): Iterator<GToken> = GTokenizerIterator(gcode)
    fun parse(gcode: Iterable<Char>): Sequence<GToken> = GTokenizerIterator(gcode.iterator()).asSequence()
    fun parse(gcode: Sequence<Char>): Sequence<GToken> = parse(gcode.iterator()).asSequence()
    fun parse(gcode: CharSequence): Sequence<GToken> = parse(gcode.iterator()).asSequence()
    fun parse(gcode: Stream<Char>): Stream<GToken> = parse(gcode.asSequence()).asStream()

    fun parseLines(gcode: Sequence<String>): Sequence<GToken> = parse(gcode.flatMap { it.asSequence() }).asSequence()
}

class GTokenizerIterator(private val chars: Iterator<Char>) : Iterator<GToken> {

    private var ch: Char? = null

    override fun hasNext() = ch != null || chars.hasNext()

    override fun next(): GToken {
        ch = ch ?: chars.next()
        return when {
            ch!!.isWhitespace() -> space(ch!!)
            ch!!.isLetter() -> ident(ch!!)
            ch!!.isDigit() || ch == '.' || ch == '-' || ch == '+' -> number(ch!!)
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

    fun space(current: Char): GToken {
        ch = null
        return when (current) {
            ' ' -> GSpace
            '\t' -> GTab
            '\n' -> GLineBreak()
            '\r' -> {
                if (chars.hasNext()) {
                    val next = chars.next()
                    if (next == '\n') {
                        // CR and LF belong to one break token, both characters are consumed.
                        GLineBreak("\r\n")
                    } else {
                        // A lone CR is not a break: keep the lookahead for the next token.
                        ch = next
                        GUnknown('\r')
                    }
                } else GUnknown(current)
            }

            else -> GUnknown(current)
        }
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
            if (current == null || !(current.isDigit() || current == '.')) {
                ch = current
                return GUnknown(raw.toString())
            }
        }

        while (current != null) {
            when {
                current.isDigit() -> digits++
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
            else -> text.toIntOrNull()?.let { GInt(it, text) } ?: GUnknown(text)
        }
    }

    fun string(): GToken {
        ch = null
        val str = StringBuilder()
        while (chars.hasNext()) {
            var c = chars.next()
            if (c == '"') {
                if (chars.hasNext()) {
                    c = chars.next()
                    if (c == '"') {
                        str.append(c)
                    } else {
                        ch = c;
                        break
                    }
                }
            } else str.append(c)
        }
        return GQuotedString(str.toString())
    }

    fun ident(current: Char): GToken {
        ch = null
        return if (current.isLetter()) GLetter(current) else GUnknown(current)
    }

    fun expression(start: Char): GToken {
        val expression = StringBuilder(start.toString())
        var stack = 1;
        while (chars.hasNext() && stack > 0) {
            ch = chars.next();
            expression.append(ch);
            when (ch) {
                '{' -> stack++
                '}' -> stack--
            }
        }
        ch = null
        return if (stack == 0) GRawExpression(expression.toString()) else GUnknown(expression.toString())
    }

    fun tailComment(): GComment {
        val str = StringBuilder()
        ch = null
        while (chars.hasNext()) {
            ch = chars.next()
            if (ch != '\n') str.append(ch) else break
        }
        if (ch != '\n') ch = null
        return GTailComment(str.toString())
    }

    fun inlineComment(start: Char): GToken {
        val comment = StringBuilder()
        var stack = 1;
        while (chars.hasNext() && stack > 0) {
            ch = chars.next();
            comment.append(ch);
            when (ch) {
                '(' -> stack++
                ')' -> stack--
            }
        }
        if (ch == ')') ch = null
        return if (stack == 0) GInlineComment(comment.toString()) else GUnknown(comment.toString())
    }
}
