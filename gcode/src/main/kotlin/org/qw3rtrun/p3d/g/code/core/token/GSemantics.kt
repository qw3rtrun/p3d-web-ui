package org.qw3rtrun.p3d.g.code.core.token

sealed interface GSemantic {
    val raw: List<GToken>
}

sealed interface GWord : GSemantic {
    val id: GIdentifier
    fun isLetter(l: Char): Boolean = id.isLetter(l)
}

sealed interface GParameter<out V : GValue> : GWord {
    val value: V
}

sealed class GFlag : GWord

data class GMeaningless(override val raw: List<GToken>) : GSemantic {
    constructor(token: GToken) : this(listOf(token))
}

sealed interface GLine {
    val payload: List<GSemantic>
    fun raw(): List<GToken> = payload.flatMap { it.raw }
    fun meaningful(): List<GWord> = payload.filterIsInstance<GWord>()
}

sealed interface GOrdered : GLine {
    val number: GInt
}

sealed interface GCheckSumControlled : GLine {
    val checksum: GParameterWord<GInt>
}

/**
 * A line that carries no command: empty, or nothing but whitespace and/or comments. Spec section 5
 * calls such a line a no-op. It keeps its tokens so the line still reproduces its input.
 */
data class GMeaninglessLine(override val payload: List<GSemantic>) : GLine


data class GSimpleLine(override val payload: List<GSemantic>) : GLine

data class GPacketLine(
    override val number: GInt,
    override val payload: List<GSemantic>,
    override val checksum: GParameterWord<GInt>,
    val raw: List<GSemantic>,
) : GLine, GOrdered, GCheckSumControlled

data class GCommand(val head: GParameterWord<GInt>, val params: List<GWord> = emptyList()) {
    constructor(cmdId: GIdentifier, cmdNum: GInt, params: List<GWord> = emptyList()) : this(
        GParameterWord(cmdId, cmdNum), params
    )

    fun print(): List<GToken> = head.raw + params.flatMap { it.raw }
}

sealed interface GError : GLine {
    val msg: String
}

data class GNotIdentifierError(val head: GValue, override val payload: List<GSemantic>) : GError {
    override val msg: String
        get() = "GCode should start with a letter, but '${head.rawText()}'"
}

/**
 * Spec section 7.3: a line number without a checksum.
 *
 * Over a serial link this is a structural error and the firmware rejects the line
 * (`Error:No Checksum with line number`). In a file it is harmless and fairly common, which is why
 * the liner reports the structure and leaves the severity to the caller rather than refusing to
 * parse. The corpus fixture contains two such lines.
 */
data class GMissingChecksum(val number: GInt?, override val payload: List<GSemantic>) : GError {
    override val msg: String
        get() = "line number ${number?.rawText() ?: "?"} has no checksum"
}

/** Spec section 7.3: a checksum without a line number. */
data class GMissingLineNumber(override val payload: List<GSemantic>) : GError {
    override val msg: String
        get() = "checksum without a line number"
}

/** Spec section 7.1: `N` is present and paired with a `*`, but is not followed by an integer. */
data class GMalformedLineNumber(override val payload: List<GSemantic>) : GError {
    override val msg: String
        get() = "'N' is not followed by a line number"
}

/** Spec section 8.1: `*` is present and paired with an `N`, but is not followed by an integer. */
data class GMalformedChecksum(val number: GInt, override val payload: List<GSemantic>) : GError {
    override val msg: String
        get() = "'*' is not followed by a checksum value on line ${number.rawText()}"
}
