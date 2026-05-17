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
            ch!!.isDigit() || ch == '.' -> number(ch!!)
            ch == '\"' -> string()
            ch == '{' -> expression('{')
            ch == ';' -> tailComment()
            ch == '(' -> inlineComment('(')
            ch == '*' -> {
                ch = null;
                return GChecksum
            }

            else -> {
                val unknown = GUnknown(ch!!)
                ch = null
                return unknown
            }
        }
    }

    fun space(current: Char): GToken {
        ch = null
        return when (current) {
            ' ' -> GSpace
            '\n' -> GLineBreak()
            '\r' -> {
                if (chars.hasNext()) {
                    ch = chars.next()
                    if (ch == '\n') GLineBreak("\r\n") else GUnknown('\r')
                } else GUnknown(current)
            }

            else -> GUnknown(current);
        }
    }

    fun number(start: Char): GNumber {
        val str = StringBuilder(start.toString())
        var decimal = false
        var current = start
        ch = null
        while (chars.hasNext()) {
            current = chars.next()
            when {
                current.isDigit() -> str.append(current)
                current == '.' -> {
                    str.append(current)
                    decimal = true;
                }

                else -> {
                    ch = current
                    break
                }

            }
        }
        val value = BigDecimal(str.toString())

        return if (decimal) GFloat(value) else GInt(value.toInt())
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
