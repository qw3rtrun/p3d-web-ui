package org.qw3rtrun.p3d.g.code.core.token

import java.math.BigDecimal

sealed interface GToken {
    fun rawText(): String
    fun toSeq() = sequenceOf(this)
}

sealed interface GValue : GToken {
    override fun toSeq(): Sequence<GValue> = sequenceOf(this)
}

sealed interface GLiteral : GValue

sealed interface GIdentifier : GToken {
    val name: String
    override fun rawText() = name
    fun isLetter(l: Char): Boolean = name == l.toString().lowercase() || name == l.toString().uppercase()
}

data class GUnknown(val str: String) : GToken {
    constructor(ch: Char) : this(ch.toString())

    override fun rawText(): String = str
}

data class GLetter(val letter: Char) : GIdentifier {
    override val name: String
        get() = letter.toString()
}

data object GChecksum : GIdentifier {
    override val name: String
        get() = "*"
}

sealed interface GComment : GToken {
    val string: String
}

data class GTailComment(override val string: String, val key: String = ";") : GComment {
    override fun rawText(): String = "$key$string"
}

data class GInlineComment(override val string: String, val start: String = "(", val end: String = ")") : GComment {
    override fun rawText(): String = "$start$string$end"
}

sealed interface GSeparator : GToken
sealed interface GWhitespace : GSeparator {
    val char: Char
    override fun rawText(): String = char.toString()
}

data object GSpace : GWhitespace {
    override val char: Char = ' ';
}

data object GTab : GWhitespace {
    override val char: Char = '\t';
}

data class GLineBreak(val breaker: String = "\n") : GSeparator {
    override fun toString(): String = this.javaClass.simpleName
    override fun rawText(): String = breaker
}

sealed interface GString : GLiteral {
    val string: String
}

/**
 * A numeric value, spec section 3.1.
 *
 * A number token carries both its parsed [number] and the [lexeme] it was read from, because the two
 * are not interchangeable: `01`, `+5`, `.5` and `1.` are all valid input whose canonical rendering
 * differs from what was written. [rawText] returns the lexeme, so the token stream reproduces its
 * input byte for byte; the lexeme is part of token identity for the same reason.
 */
sealed interface GNumber : GLiteral {
    val number: Number

    /** The exact characters this number was lexed from, sign and non-canonical digits included. */
    val lexeme: String

    override fun rawText(): String = lexeme
}

data class GQuotedString(override val string: String) : GString {
    override fun rawText(): String = "\"${string.replace("\"", "\"\"")}\""
}

// This is a GCODE design gap, when some string parameters have no quote.
// The problem is it depends only on the command's number itself, so it can be caught only when a semantic reveal
data class GUnquotedString(override val string: String) : GString {
    override fun rawText(): String = "\"${string.replace("\"", "\"\"")}\""
}

data class GInt(val int: Int, override val lexeme: String = int.toString()) : GNumber {
    override val number: Number
        get() = int
}

data class GFloat(val float: BigDecimal, override val lexeme: String = float.toString()) : GNumber {
    constructor(string: String) : this(BigDecimal(string), string)
    constructor(int: Int) : this(BigDecimal(int))

    // BigDecimal(Double) would expand the exact binary value (1.05 -> 1.05000000000000004440892...).
    constructor(float: Double) : this(BigDecimal.valueOf(float))

    override val number: Number
        get() = float
}

sealed interface GExpression : GValue {
    val exception: String
}

data class GRawExpression(override val exception: String) : GExpression {
    override fun rawText(): String = exception
}


fun Int.toToken() = GInt(this)
fun Double.toToken() = GFloat(this)
fun BigDecimal.toToken() = GFloat(this)
fun Char.toToken() = GLetter(this)
fun String.toToken() = GQuotedString(this)
