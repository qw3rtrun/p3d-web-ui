package org.qw3rtrun.p3d.g.code.core.block

import org.qw3rtrun.p3d.g.code.core.token.GEmptyId
import org.qw3rtrun.p3d.g.code.core.token.GIdentifier
import org.qw3rtrun.p3d.g.code.core.token.GNumber
import org.qw3rtrun.p3d.g.code.core.token.GString
import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.GValue

/**
 * A field: an identifier and, usually, the value behind it (GCODE_spec.md section 3).
 *
 * Words belong to the **command** layer, not the line layer. A line is classified from its tokens -
 * where `N` is and where the last `*` is - and nothing above that needs a word until someone asks
 * what the line *commands*. The **writing** direction is where they are produced now: the DSL and
 * `GRq.encode()` build them, and `GEncoder` consumes them. Reading a line back into words - the
 * command-agnostic decomposition the old `GCommandParser` did - is measurement apparatus and lives
 * with the corpus tests; a host that wants to know what a line commands asks a decoder, which knows
 * the command number and can therefore read what words cannot (spec 3.4a).
 *
 * ```
 * GParameterWord(GLetter('X'), GInt(10)).isLetter('x')   // true
 * ```
 *
 * @property id the field's identifier
 * @property raw the tokens this word was read from, spelling included: `X  10` is one word holding
 *   two spaces. Provenance, not identity, and not what round-trips a line - `GLine.raw` is. A word
 *   built by the DSL carries the canonical `[id, value]`; one read from `G 1` carries the space as
 *   well.
 */
sealed interface GWord {
    val id: GIdentifier

    val raw: List<GToken>

    /**
     * Whether this word's identifier is the letter [l], case-insensitively - `GIdentifier.isLetter`.
     *
     * ```
     * GParameterWord(GLetter('X'), GInt(10)).isLetter('x')   // true
     * ```
     *
     * @param l the letter to compare against, in either case
     * @return true when [id] is that letter
     */
    fun isLetter(l: Char): Boolean = id.isLetter(l)
}

/**
 * A field that carries a value: `X10`, `N1`, `*57`, and `X 10` - spec 2.1 lets a space separate.
 *
 * ```
 * GParameterWord(GLetter('X'), GInt(10)).raw   // [GLetter('X'), GInt(10)]
 * ```
 *
 * @property value the field's value
 */
data class GParameterWord<V : GValue>(
    override val id: GIdentifier,
    val value: V,
    override val raw: List<GToken> = listOf(id, value),
) : GWord

/**
 * A field with no value after it: the `X` of `G28 X Y`, or a `*` with nothing usable behind it.
 *
 * ```
 * GEncoder.encode(GCommand(GLetter('G'), GInt(28), listOf(GFlagWord(GLetter('X')))))   // "G28 X"
 * ```
 */
data class GFlagWord(
    override val id: GIdentifier,
    override val raw: List<GToken> = listOf(id),
) : GWord

/**
 * A field with no identifier in front of it: spec 3.4a's bare rest-of-line string, as in
 * `M117 Hello World`, where the text *is* the argument.
 *
 * Only a decoder builds one, because only a decoder knows the command number that says a line has
 * such an argument at all (see `GRqDecoder`); the token layer lexes `Hello World` letter by letter
 * and the command parser never assembles one.
 *
 * [id] is [org.qw3rtrun.p3d.g.code.core.token.GEmptyId] so that a `GWord` always has one and `GEncoder` can write it unconditionally -
 * it renders as nothing. That is the compromise: the honest shape splits `GWord` into a named half
 * and an unnamed one and lets `GEmptyId` disappear, which touches the DSL's parameter check, the
 * parser's structural test, the encoder and every generated `encode()`. Not worth it for one
 * unnamed word type; revisit when there is a second.
 *
 * This replaced a `GUnnamedWord<V : GValue>` interface whose type parameter appeared nowhere in
 * its body and which had this as its only implementation.
 *
 * ```
 * val message = GUnnamedStr(GUnquotedString("Hello World"))
 * GEncoder.encode(GCommand(GLetter('M'), GInt(117), listOf(message)))   // "M117 Hello World"
 * ```
 *
 * @property str the string itself
 */
data class GUnnamedStr(
    val str: GString,
    override val raw: List<GToken> = listOf(str),
) : GWord {
    override val id: GIdentifier
        get() = GEmptyId
}

/**
 * Something that can stand in a [GBlock]: a [GCommand] or a [org.qw3rtrun.p3d.g.code.core.token.GComment].
 *
 * This is the *build* direction. A parsed line carries the exact tokens it was read from,
 * whitespace included; a block being built carries only what the author chose, and the encoder
 * decides the bytes. Keeping them apart is what lets `GEncoder` emit one canonical spelling instead
 * of having to guess which of a parsed line's spaces were meaningful.
 *
 * ```
 * GEncoder.encode(GBlock(GCommand(GLetter('G'), GInt(28)), GTailComment(" note")))   // "G28 ; note"
 * ```
 *
 * **Temporarily not sealed.** A sealed interface may only be implemented in its own package, and
 * `GComment` still lives in `core.token`. This stays open only until `GComment` stops being a block
 * part (a `GCommentPart` wrapper takes its place); it is sealed again in that change.
 */
interface GBlockPart

/**
 * A line being built: spec section 5's "line (block)", as a value the encoder can render.
 *
 * Ordered, and general enough for every shape section 5 allows - a command, a command with a
 * trailing comment, a comment on its own, several commands, or a comment between two of them. What
 * it does **not** carry is the `N` field or the `*` field: those are framing, they are added by
 * `GEncoder.frame`, and a block that carried them could be framed twice.
 *
 * ```
 * val block = GBlock(GCommand(GLetter('G'), GInt(28)), GTailComment(" note"))
 * GEncoder.encode(block)       // "G28 ; note"
 * GEncoder.frame(1, block)     // "N1 G28*18 ; note"
 * ```
 *
 * @property parts the commands and comments, in wire order
 */
data class GBlock(val parts: List<GBlockPart>) {
    constructor(vararg parts: GBlockPart) : this(parts.toList())

    /**
     * The commands in wire order, comments dropped.
     *
     * ```
     * val g28 = GCommand(GLetter('G'), GInt(28))
     * val m105 = GCommand(GLetter('M'), GInt(105))
     * GBlock(g28, GTailComment("x"), m105).commands()   // [g28, m105]
     * ```
     *
     * @return every [GCommand] among [parts], in order
     */
    fun commands(): List<GCommand> = parts.filterIsInstance<GCommand>()
}

/**
 * One command and its parameters, per spec section 4. [head] carries a `GNumber`, not a `GInt`,
 * because spec 4.1 lets a command number carry a **subcode** - `G29.1` is one command word whose
 * value is `GFloat("29.1")`, and keeping the lexeme is what re-emits `29.1` rather than `29` + `.1`.
 *
 * There is deliberately no `print()` here any more. It returned `head.raw + params.flatMap { raw }`
 * - a token list with no separators in it - and left the caller to join them, which is how it could
 * emit bytes that depended on the values happening to be self-delimiting (finding 1.10). Encoding is
 * `GEncoder`, it produces a `String`, and it is a `String` because the checksum covers the bytes as
 * transmitted (spec 8.3) and so whitespace is part of the output, not an afterthought.
 *
 * ```
 * GEncoder.encode(GCommand(GLetter('G'), GFloat("29.1")))   // "G29.1"
 * ```
 *
 * @property head the command word: its letter and its number
 * @property params the parameter words, in wire order
 */
data class GCommand(val head: GParameterWord<GNumber>, val params: List<GWord> = emptyList()) : GBlockPart {
    constructor(cmdId: GIdentifier, cmdNum: GNumber, params: List<GWord> = emptyList()) : this(
        GParameterWord(cmdId, cmdNum), params
    )
}
