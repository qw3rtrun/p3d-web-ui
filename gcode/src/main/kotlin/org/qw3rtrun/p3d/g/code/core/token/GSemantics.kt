package org.qw3rtrun.p3d.g.code.core.token

sealed interface GSemantic

sealed interface GLine : GSemantic {
    val payload: List<GToken>
}

sealed interface GOrdered : GLine {
    val number: GInt
}

data class GCheckSumValue(val ident: GChecksum, val value: GInt)

sealed interface GCheckSumControlled : GLine {
    val checksum: GCheckSumValue
}

/**
 * A line that carries no command: empty, or nothing but whitespace and/or comments. Spec section 5
 * calls such a line a no-op. It keeps its tokens so the line still reproduces its input.
 */
data class GEmptyLine(override val payload: List<GToken>) : GLine {
    constructor() : this(emptyList())
}

data class GSimpleLine(override val payload: List<GToken>) : GLine

data class GPacketLine(
    override val number: GInt,
    override val payload: List<GToken>,
    override val checksum: GCheckSumValue,
    val tail: List<GToken>,
) : GLine, GOrdered, GCheckSumControlled

data class GCommand(val head: GIdentifier, val params: List<GElement> = emptyList()) : GSemantic {
    constructor(headPair: Pair<GIdentifier, GElement>, params: List<GElement> = emptyList()) : this(
        headPair.first,
        listOf(headPair.second) + params
    )

    fun print(): String = buildString {
        append(head.rawText())
        for (param in params) {
            append(param.rawText())
        }
    }
}

data class GCommandLine(val cmds: List<GCommand>, override val payload: List<GToken>) : GLine

sealed interface GError : GLine {
    val msg: String
}

data class GNotIdentifierError(val head: GElement, override val payload: List<GToken>) : GError {
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
data class GMissingChecksum(val number: GInt?, override val payload: List<GToken>) : GError {
    override val msg: String
        get() = "line number ${number?.rawText() ?: "?"} has no checksum"
}

/** Spec section 7.3: a checksum without a line number. */
data class GMissingLineNumber(override val payload: List<GToken>) : GError {
    override val msg: String
        get() = "checksum without a line number"
}

/** Spec section 7.1: `N` is present and paired with a `*`, but is not followed by an integer. */
data class GMalformedLineNumber(override val payload: List<GToken>) : GError {
    override val msg: String
        get() = "'N' is not followed by a line number"
}

/** Spec section 8.1: `*` is present and paired with an `N`, but is not followed by an integer. */
data class GMalformedChecksum(val number: GInt, override val payload: List<GToken>) : GError {
    override val msg: String
        get() = "'*' is not followed by a checksum value on line ${number.rawText()}"
}
