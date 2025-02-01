package org.qw3rtrun.p3d.g.code.core.token

import java.math.BigDecimal

sealed interface GToken {
    fun rawText(): String
}

sealed interface GLiteral : GToken

sealed interface GIdentifier : GToken {
    val letter: Char
    override fun rawText() = letter.toString()
}

data class GUnknown(val str: String) : GToken {
    constructor(ch: Char) : this(ch.toString())

    override fun rawText(): String = str
}

data class GField(override val letter: Char) : GIdentifier

sealed interface GChecksum : GToken

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

data object GTag : GWhitespace {
    override val char: Char = '\t';
}

data class GNewline(val breaker: String = "\n") : GSeparator {
    override fun toString(): String = this.javaClass.simpleName
    override fun rawText(): String = breaker
}

sealed interface GString : GLiteral {
    val string: String
}

sealed interface GNumber : GLiteral {
    val number: Number
}

data class GQuotedString(override val string: String) : GString {
    override fun rawText(): String = "s\"${string.replace("\"", "\"\"")}\""
}

data class GInt(val int: Int) : GNumber {
    override val number: Number
        get() = int

    override fun rawText(): String = int.toString()
}

data class GFloat(val float: BigDecimal) : GNumber {
    constructor(string: String) : this(BigDecimal(string))
    constructor(int: Int) : this(BigDecimal(int))
    constructor(float: Double) : this(BigDecimal(float))

    override val number: Number
        get() = float

    override fun rawText(): String = float.toString()
}

sealed interface GExpression : GLiteral {
    val exception: String
}

data class GRawExpression(override val exception: String) : GExpression {
    override fun rawText(): String = exception
}
