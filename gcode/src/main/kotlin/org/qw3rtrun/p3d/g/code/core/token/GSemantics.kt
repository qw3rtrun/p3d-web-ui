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

/**
 * One command and its parameters, per spec section 4. [head] carries a `GNumber`, not a `GInt`,
 * because spec 4.1 lets a command number carry a **subcode** - `G29.1` is one command word whose
 * value is `GFloat("29.1")`, and keeping the lexeme is what re-emits `29.1` rather than `29` + `.1`.
 */
data class GCommand(val head: GParameterWord<GNumber>, val params: List<GWord> = emptyList()) {
    constructor(cmdId: GIdentifier, cmdNum: GNumber, params: List<GWord> = emptyList()) : this(
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

/**
 * Spec section 8.1: `*` is paired with an `N`, but what follows it is not a well-formed checksum
 * field. Two shapes, both decided on syntax alone and before any algorithm runs:
 *
 * - nothing usable after the marker - `N1*`, `*ABC`, `*10.5`;
 * - an integer of a width no algorithm claims. Section 8.1 gives the digit count the job of choosing
 *   between them, so 1-3 digits and 5 digits are checksum fields and **4, 6 or more are not**.
 *   `*1234` is not a mismatch: there is nothing to compare it against.
 */
data class GMalformedChecksum(val number: GInt, override val payload: List<GSemantic>) : GError {
    override val msg: String
        get() = "'*' is not followed by a checksum value on line ${number.rawText()}"
}

/**
 * Spec section 8.5: the line is framed correctly and its checksum field is well formed, but the
 * value on the wire is not the value the bytes produce. The line is corrupt in transit and a host
 * answers it with `Resend: <number>`.
 *
 * [expected] is recomputed from the covered bytes, [received] is what the wire carried. Both are
 * kept because a host's diagnostic quotes them together, and because the pair is what tells a
 * genuine corruption from a generator that is checksumming the wrong byte range.
 *
 * It takes the full element list as [payload], like every other error type, so the line round-trips
 * for free. [GOrdered] because the resend request is addressed by line number. Deliberately **not**
 * [GCheckSumControlled]: a consumer matching on that interface is asking for lines it can trust.
 */
data class GCheckSumFailedLine(
    override val number: GInt,
    val expected: GInt,
    val received: GInt,
    override val payload: List<GSemantic>,
) : GError, GOrdered {
    override val msg: String
        get() = "checksum mismatch on line ${number.rawText()}: " +
                "computed ${expected.rawText()}, received ${received.rawText()}"
}
