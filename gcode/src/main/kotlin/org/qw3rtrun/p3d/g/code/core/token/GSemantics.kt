package org.qw3rtrun.p3d.g.code.core.token

sealed interface GSemantic {
    val raw: List<GToken>
}

sealed interface GWord : GSemantic {
    val id: GIdentifier
    fun isLetter(l: Char): Boolean = id.isLetter(l)
}

/** A field that carries a value: `X10`, `N1`, `*57`, and `X 10` - spec 2.1 lets a space separate. */
data class GParameterWord<V : GValue>(
    override val id: GIdentifier,
    val value: V,
    override val raw: List<GToken> = listOf(id, value),
) : GWord

/** A field with no value after it: the `X` of `G28 X Y`, or a `*` with nothing usable behind it. */
data class GFlagWord(
    override val id: GIdentifier,
    override val raw: List<GToken> = listOf(id),
) : GWord

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

/**
 * A framed line: `N<n> <body>*<cs>`. It is the only line type that **decomposes** its input - the
 * `N` field, the `*` field and anything after them are not in [payload] - so it is the only one that
 * has to say how to print itself back. [whole] carries every element, in wire order.
 */
data class GPacketLine(
    override val number: GInt,
    override val payload: List<GSemantic>,
    override val checksum: GParameterWord<GInt>,
    val whole: List<GSemantic>,
) : GLine, GOrdered, GCheckSumControlled {

    /**
     * Over [whole], not [payload]: the inherited `payload.flatMap` silently dropped the line number,
     * the checksum and the terminator, so a parsed packet re-printed as its own body alone -
     * `N1 G28*12` came back as ` G28` (TODO 1.20). The lost field was also named `raw`, which
     * shadowed this function and is why the round-trip suite could not see it.
     */
    override fun raw(): List<GToken> = whole.flatMap { it.raw }
}

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
