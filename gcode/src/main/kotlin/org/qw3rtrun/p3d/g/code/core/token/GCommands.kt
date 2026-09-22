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
