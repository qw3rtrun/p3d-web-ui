package org.qw3rtrun.p3d.g.code.core.token

import org.qw3rtrun.p3d.g.code.core.CheckSum
import org.qw3rtrun.p3d.g.code.core.XorCheckSum
import java.math.BigDecimal
import java.util.stream.Stream

class GTokenizerStream(private val line: String) {

    private val chars = line.toCharArray()
    private val checksum: CheckSum = XorCheckSum()

    var pointer = 0

    val tokens: MutableList<GToken> = ArrayList<GToken>()

    fun parse(input: Stream<String>): Stream<GToken> {
        while (pointer < chars.size) {
            val c = chars[pointer]
            when {
                c.isWhitespace() -> space()
                c.isLetter() -> ident()
                c.isDigit() || c == '.' -> number()
                c == '\"' -> string()
                c == '{' -> expression()
                c == ';' -> comment()
                else -> {
                    tokens.add(GUnknown(c))
                    pointer++
                }
            }
        }
        return tokens.stream()
    }

    fun space() {
        when (chars[pointer]) {
            ' ' -> {
                tokens.add(GWhitespace)
                pointer++
            }

            '\n' -> {
                tokens.add(GNewline)
                pointer++
            }

            else -> return;
        }
    }

    fun number() {
        val start = pointer
        var decimal = false
        while (pointer < chars.size && (chars[pointer].isDigit() || chars[pointer] == '.')) {
            if (chars[pointer] == '.') {
                decimal = true
            }
            pointer++
        }
        val value = BigDecimal(String(chars.copyOfRange(start, pointer)))

        tokens.add(if (decimal) GFloat(value) else GInt(value.toInt()))
    }

    fun string() {
        val res = chars.copyOfRange(pointer++, chars.size)
        var index = 0;
        while (pointer < chars.size) {
            if (chars[pointer] != '"') {
                res[index++] = chars[pointer++]
            } else if (++pointer < chars.size && chars[pointer] == '"') {
                res[index++] = chars[pointer++]
            } else break;
        }
        tokens.add(GQuotedString(String(res, 0, index)))
    }

    fun ident() {
        if (chars[pointer].isLetter()) {
            tokens.add(GField(chars[pointer]))
            pointer++
        }
    }

    fun expression() {
        val start = pointer;
        var stack = 1;
        while (pointer++ < chars.size && stack > 0) when (chars[pointer]) {
            '{' -> stack++
            '}' -> stack--
        }
        tokens.add(GRawExpression(String(chars.copyOfRange(start, pointer))))
    }

    fun comment() {
        val start = pointer;
        while (pointer++ < chars.size && chars[pointer] != '\n')
            tokens.add(GLineComment(String(chars.copyOfRange(start, pointer))))
    }

}
