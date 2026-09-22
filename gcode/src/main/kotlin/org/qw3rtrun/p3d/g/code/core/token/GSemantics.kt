package org.qw3rtrun.p3d.g.code.core.token

/**
 * A field: an identifier and, usually, the value behind it (GCODE_spec.md section 3).
 *
 * Words belong to the **command** layer, not the line layer. A line is classified from its tokens -
 * where `N` is and where the last `*` is - and nothing above that needs a word until someone asks
 * what the line *commands*. `GCommandParser` is where tokens become words, and it is the only
 * producer of the types below.
 */
sealed interface GWord {
    val id: GIdentifier

    /**
     * The tokens this word was read from, spelling included: `X  10` is one word holding two spaces.
     *
     * Provenance, not identity, and not what round-trips a line - `GLine.raw` is. A word built by
     * the DSL carries the canonical `[id, value]`; one read from `G 1` carries the space as well.
     */
    val raw: List<GToken>

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

sealed interface GUnnamedWord<V : GValue> : GWord {
    override val id: GIdentifier
        get() = GEmptyId
}

data class GUnnamedStr(val str: GString,
                       override val raw: List<GToken>
) : GUnnamedWord<GString>

/**
 * A line, as the liner read it off the token stream (GCODE_spec.md section 5).
 *
 * **A line knows tokens and nothing else.** Its shape is decided from two positions - the first
 * identifier and the last `*` - and both are found by walking tokens, so the line layer never builds
 * a word and never has to say what a word *is*. Words are the command layer's vocabulary
 * ([GCommandParser]), and a consumer that only routes, resends or re-prints lines never pays for
 * them.
 *
 * [raw] is every token, in wire order. It is the stored, primary value of every line type, which is
 * what makes "a line reproduces its input" true by construction rather than by an override that a
 * decomposing type has to remember to write - the mistake that once re-printed `N1 G28*12` as
 * ` G28`.
 */
sealed interface GLine {

    /** Every token of the line, in wire order - leading whitespace and the terminator included. */
    val raw: List<GToken>

    /**
     * The tokens a command may be read from: the whole line, except for a framed one, where it is
     * what sits between the line-number field and the checksum marker.
     */
    val body: List<GToken>
        get() = raw
}

sealed interface GOrdered : GLine {
    val number: GInt
}

sealed interface GCheckSumControlled : GLine {
    val checksum: GInt
}

/**
 * A line that carries no command: empty, or nothing but whitespace and/or comments. Spec section 5
 * calls such a line a no-op. It keeps its tokens so the line still reproduces its input.
 */
data class GMeaninglessLine(override val raw: List<GToken>) : GLine

data class GSimpleLine(override val raw: List<GToken>) : GLine

/**
 * A framed line: `N<n> <body>*<cs>`, spec sections 7 and 8.
 *
 * The only line type that **decomposes** its input, and it does so by pointing into it: [body] is a
 * slice of [raw] running from just past the line number to the marker, so the two cannot disagree
 * and nothing is copied.
 *
 * Where the slice ends is a rule, not a detail. Spec section 5 puts the `*` field last, and Marlin
 * terminates the command at the marker (`get_serial_commands` writes a `\0` over it), so
 * `N1 G28*18 G1 X5` is **one** command - the `G1` is outside the frame and outside the checksum.
 *
 * **A [GPacketLine] is only ever built for a line whose checksum has been verified** (spec section
 * 8), so holding one means the line is intact; there is no `verify()` for a caller to forget.
 */
data class GPacketLine(
    override val number: GInt,
    override val checksum: GInt,
    override val body: List<GToken>,
    override val raw: List<GToken>,
) : GLine, GOrdered, GCheckSumControlled

/**
 * Something that can stand in a [GBlock]: a [GCommand] or a [GComment].
 *
 * This is the *build* direction. A parsed line carries the exact tokens it was read from,
 * whitespace included; a block being built carries only what the author chose, and the encoder
 * decides the bytes. Keeping them apart is what lets `GEncoder` emit one canonical spelling instead
 * of having to guess which of a parsed line's spaces were meaningful.
 */
sealed interface GBlockPart

/**
 * A line being built: spec section 5's "line (block)", as a value the encoder can render.
 *
 * Ordered, and general enough for every shape section 5 allows - a command, a command with a
 * trailing comment, a comment on its own, several commands, or a comment between two of them. What
 * it does **not** carry is the `N` field or the `*` field: those are framing, they are added by
 * `GEncoder.frame`, and a block that carried them could be framed twice.
 */
data class GBlock(val parts: List<GBlockPart>) {
    constructor(vararg parts: GBlockPart) : this(parts.toList())

    /** The commands in wire order, comments dropped. */
    fun commands(): List<GCommand> = parts.filterIsInstance<GCommand>()
}

/**
 * One command and its parameters, per spec section 4. [head] carries a `GNumber`, not a `GInt`,
 * because spec 4.1 lets a command number carry a **subcode** - `G29.1` is one command word whose
 * value is `GFloat("29.1")`, and keeping the lexeme is what re-emits `29.1` rather than `29` + `.1`.
 */
data class GCommand(val head: GParameterWord<GNumber>, val params: List<GWord> = emptyList()) : GBlockPart {
    constructor(cmdId: GIdentifier, cmdNum: GNumber, params: List<GWord> = emptyList()) : this(
        GParameterWord(cmdId, cmdNum), params
    )

    // There is deliberately no print() here any more. It returned `head.raw + params.flatMap { raw }`
    // - a token list with no separators in it - and left the caller to join them, which is how it
    // could emit bytes that depended on the values happening to be self-delimiting (finding 1.10).
    // Encoding is `GEncoder`, it produces a String, and it is a String because the checksum covers
    // the bytes as transmitted (spec 8.3) and so whitespace is part of the output, not an afterthought.
}

sealed interface GError : GLine {
    val msg: String
}

data class GNotIdentifierError(val head: GValue, override val raw: List<GToken>) : GError {
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
data class GMissingChecksum(val number: GInt?, override val raw: List<GToken>) : GError {
    override val msg: String
        get() = "line number ${number?.rawText() ?: "?"} has no checksum"
}

/** Spec section 7.3: a checksum without a line number. */
data class GMissingLineNumber(override val raw: List<GToken>) : GError {
    override val msg: String
        get() = "checksum without a line number"
}

/** Spec section 7.1: `N` is present and paired with a `*`, but is not followed by an integer. */
data class GMalformedLineNumber(override val raw: List<GToken>) : GError {
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
data class GMalformedChecksum(val number: GInt, override val raw: List<GToken>) : GError {
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
 * It keeps the whole token list, like every other line type, so it round-trips for free. [GOrdered]
 * because the resend request is addressed by line number. Deliberately **not**
 * [GCheckSumControlled]: a consumer matching on that interface is asking for lines it can trust.
 */
data class GCheckSumFailedLine(
    override val number: GInt,
    val expected: GInt,
    val received: GInt,
    override val raw: List<GToken>,
) : GError, GOrdered {
    override val msg: String
        get() = "checksum mismatch on line ${number.rawText()}: " +
                "computed ${expected.rawText()}, received ${received.rawText()}"
}
