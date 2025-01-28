package org.qw3rtrun.p3d.g.code.core.token

import java.math.BigDecimal

sealed interface GToken

sealed interface GLiteral : GToken

sealed interface GIdentifier : GToken {
    val letter: Char
}

data class GUnknown(val char: Char) : GToken

data class GField(override val letter: Char) : GIdentifier

sealed interface GChecksum : GToken

sealed interface GComment : GToken {
    val string: String
}

data class GLineComment(override val string: String) : GComment {}

sealed interface GSeparator : GToken
data object GWhitespace : GSeparator
data object GNewline : GSeparator

sealed interface GString : GLiteral {
    val string: String
}

sealed interface GNumber : GLiteral {
    val number: Number
}

data class GQuotedString(override val string: String) : GString
data class GInt(val int: Int) : GNumber {
    override val number: Number
        get() = int
}

data class GFloat(val float: BigDecimal) : GNumber {
    constructor(string: String) : this(BigDecimal(string))
    constructor(int: Int) : this(BigDecimal(int))
    constructor(float: Double) : this(BigDecimal(float))

    override val number: Number
        get() = float
}

sealed interface GExpression : GLiteral {
    val exception: String
}

data class GRawExpression(override val exception: String) : GExpression
