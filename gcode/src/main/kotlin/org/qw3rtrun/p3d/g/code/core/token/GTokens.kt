package org.qw3rtrun.p3d.g.code.core.token

import java.math.BigDecimal

/**
 * One lexical element of a G-code stream, as `GTokenizer` produces it (GCODE_spec.md sections 1-3).
 *
 * The sealed hierarchy below is the closed set of kinds: identifiers, values, comments, separators,
 * and [GUnknown] for input that is not lexically valid. Round-trip fidelity is the invariant:
 * concatenating [rawText] over a token stream reproduces its input byte for byte.
 *
 * ```
 * GTokenizer.parse("G1 ;hi").joinToString("") { it.rawText() }   // "G1 ;hi"
 * ```
 */
sealed interface GToken {
    fun rawText(): String
}

/** A token that can stand as a field's value: a number, a string, or an expression (spec 3). */
sealed interface GValue : GToken

/** A [GValue] that is a literal - a number or string - rather than an expression to evaluate. */
sealed interface GLiteral : GValue

/**
 * A token that names a field: a letter, the checksum marker `*`, or the empty identifier of a bare
 * rest-of-line string.
 *
 * @property name the identifier's text, which is also its [rawText]
 */
sealed interface GIdentifier : GToken {
    val name: String
    override fun rawText() = name

    /**
     * Whether this identifier is the single character [l], folded ASCII-only (spec 2.2: dialects are
     * case-insensitive; spec 1.1: the wire is 7-bit ASCII, so only `a`-`z` fold). Not
     * `lowercase()`/`uppercase()`, which are locale- and Unicode-wide - U+212A KELVIN SIGN used to
     * lowercase to `k` and match `GLetter('k')`. Named for the character it compares, not `isLetter`,
     * because `GChecksum.isChar('*')` is also true. [GLetter] overrides this to compare its character
     * directly rather than through [name].
     *
     * @param l the character to compare against, in either case
     * @return true when this identifier is exactly one character that folds to the same as [l]
     */
    fun isChar(l: Char): Boolean = name.length == 1 && asciiFold(name[0]) == asciiFold(l)
}

/**
 * [c] folded to upper case, ASCII only (spec 1.1: the wire is 7-bit, so only `a`-`z` fold).
 * `internal` because the reply decoders in `protocol/` fold through this same rule.
 *
 * @param c the character to fold
 * @return the upper-case ASCII letter, or [c] unchanged
 */
internal fun asciiFold(c: Char): Char = if (c >= 'a' && c <= 'z') (c.code - 32).toChar() else c

/**
 * Input that is not lexically valid - errors are values (spec 9): a stray character, a bare sign, a
 * malformed number, an unterminated string, comment or expression. It carries the offending
 * characters verbatim, so the stream keeps going past it and still re-prints byte-identically.
 *
 * @property str the characters that did not lex
 */
data class GUnknown(val str: String) : GToken {
    constructor(ch: Char) : this(ch.toString())

    override fun rawText(): String = str
}

/**
 * A letter identifier, `A`-`Z` or `a`-`z` (spec 4.3), in the case it was written.
 *
 * @property letter the letter as written
 */
data class GLetter(val letter: Char) : GIdentifier {

    /**
     * Stored, not computed: a `get() = letter.toString()` allocated on every read, including twice
     * per call from [GIdentifier.isChar]'s default - decoding `G1 X10 Y20 E5 F1800` is on the order
     * of 48 identifier comparisons and ~96 short-lived `String`s with that implementation.
     */
    override val name: String = letter.toString()

    override fun isChar(l: Char): Boolean = asciiFold(letter) == asciiFold(l)
}

/**
 * The checksum marker `*` (spec 8). The lexer emits it for any `*` outside a comment or string; the
 * liner decides whether it is the line's checksum field.
 */
data object GChecksum : GIdentifier {
    override val name: String
        get() = "*"
}

/**
 * A comment (spec 6): a `;` comment to end of line, or a parenthesised one. Comments are not fields;
 * a `GCommentPart` in the block layer wraps one of these where a line is being built.
 *
 * @property string the comment's text, without its delimiters
 */
sealed interface GComment : GToken {
    val string: String
}

/**
 * A comment that runs to end of line: [key] then [string]. A leading space is part of the text, so
 * `; move` and `;move` both round-trip as written.
 *
 * @property key the marker the comment opens with
 */
data class GTailComment(override val string: String, val key: String = ";") : GComment {
    override fun rawText(): String = "$key$string"
}

/**
 * A parenthesised comment: [start], [string], [end]. Nested pairs inside it are part of the text.
 *
 * @property start the opening delimiter
 * @property end the closing delimiter
 */
data class GInlineComment(override val string: String, val start: String = "(", val end: String = ")") : GComment {
    override fun rawText(): String = "$start$string$end"
}

/** A token that separates fields or lines rather than carrying content: whitespace or a line break. */
sealed interface GSeparator : GToken

/**
 * One whitespace character between fields - spec 2.1's space or tab - which a field may also carry
 * between its identifier and its value.
 *
 * @property char the whitespace character
 */
sealed interface GWhitespace : GSeparator {
    val char: Char
    override fun rawText(): String = char.toString()
}

/** A single space. */
data object GSpace : GWhitespace {
    override val char: Char = ' ';
}

/** A single tab. */
data object GTab : GWhitespace {
    override val char: Char = '\t';
}

/**
 * A line terminator, spec 1.2: LF, or CRLF as one token. `toString` prints the class name so a debug
 * dump of a token list does not break across lines.
 *
 * @property breaker the terminator's characters
 */
data class GLineBreak(val breaker: String = "\n") : GSeparator {
    override fun toString(): String = this.javaClass.simpleName
    override fun rawText(): String = breaker
}

/**
 * A string value: quoted (spec 3.4) or bare rest-of-line (spec 3.4a).
 *
 * @property string the decoded text, without delimiters or escapes
 */
sealed interface GString : GLiteral {
    val string: String
}

/**
 * A numeric value, spec 3.1.
 *
 * Carries both the parsed [number] and the [lexeme] it was read from: `01`, `+5`, `.5` and `1.` are
 * all valid input whose canonical rendering differs from what was written, and [rawText] returns the
 * lexeme so the token stream round-trips byte for byte.
 *
 * The parsed value of a decimal is `java.math.BigDecimal` - a deliberate, recorded exception to this
 * module's dependency-free rule (todo 02), since arbitrary-precision decimal exists in none of the
 * port targets (JS/TS, C, Rust) without a library. It is affordable because nothing in this module
 * reads [number] - round-tripping only touches [lexeme] - and because its scale is what distinguishes
 * `1.0` from `1.00`; a port's replacement wants the same property, so a scaled integer pair
 * (`mantissa x 10^-scale`) is the right substitute, not a binary float. Spec Appendix B records the
 * same liability.
 *
 * There is deliberately no `Double` entry point: a `Double` cannot hold most authored decimals and
 * gives the caller no way to state the lexeme to render - `(0.1 + 0.2)` becomes
 * `0.30000000000000004`, 19 characters against the <=76 payload budget (spec 1.3) and against the
 * 3-5 decimals spec 3.1 asks generators to round to. A caller holding a `Double` passes a [String] or
 * rounds explicitly.
 *
 * @property number the parsed value
 * @property lexeme the exact characters this number was lexed from, sign and non-canonical digits
 *   included
 */
sealed interface GNumber : GLiteral {
    val number: Number

    val lexeme: String

    override fun rawText(): String = lexeme
}

/**
 * A quoted string, spec 3.4. Renders with its delimiters, and with every inner `"` doubled as
 * spec 3.4b escapes it.
 */
data class GQuotedString(override val string: String) : GString {
    override fun rawText(): String = "\"${string.replace("\"", "\"\"")}\""
}

/**
 * A string that reaches the wire with no delimiters: spec 3.4a's bare rest-of-line string. GCODE has
 * no quoting convention for it, and which parameters are bare-string depends on the command, which
 * the token layer does not know - so the lexer never produces this (`M117 Hello World` lexes letter
 * by letter) and a command-aware decoder builds it, through `GUnnamedStr`.
 *
 * [rawText] is the string itself, quotes included if it contains any - there is nothing to escape,
 * since the value runs to end of line and adding quotes would put them on the printer's display.
 */
data class GUnquotedString(override val string: String) : GString {
    override fun rawText(): String = string
}

/**
 * An integer number. [lexeme] defaults to the canonical rendering of [int] and keeps whatever was
 * written otherwise, so `01` and `+5` round-trip.
 *
 * @property int the value
 */
data class GInt(val int: Int, override val lexeme: String = int.toString()) : GNumber {
    override val number: Number
        get() = int
}

/**
 * A number with a decimal point. [lexeme] defaults to `BigDecimal.toString()` of [value]; the
 * `String` constructor keeps the exact characters, so `1.` and `.5` round-trip, and the `Int`
 * constructor is the integral value as a decimal.
 *
 * @property value the exact decimal value, its scale included
 */
data class GFloat(val value: BigDecimal, override val lexeme: String = value.toString()) : GNumber {
    constructor(string: String) : this(BigDecimal(string), string)
    constructor(int: Int) : this(BigDecimal(int))

    override val number: Number
        get() = value
}

/**
 * An expression value, spec 3.5: RS274/NGC's `[...]` or RepRapFirmware's `{...}`. [text] is the
 * expression as written, delimiters included - nothing here evaluates it, so a host that does not
 * understand it still re-emits it byte for byte.
 *
 * @property text the expression as written, delimiters included
 */
sealed interface GExpression : GValue {
    val text: String
}

/** An expression kept as its raw text, as the lexer reads a brace expression; it re-emits unchanged. */
data class GRawExpression(override val text: String) : GExpression {
    override fun rawText(): String = text
}


fun Int.asGInt() = GInt(this)

fun Short.asGInt() = GInt(this.toInt())

fun Byte.asGInt() = GInt(this.toInt())

fun String.asGInt() = GInt(this.toInt(), this)

fun BigDecimal.asGFloat() = GFloat(this)

fun String.asGFloat() = GFloat(this)

fun Int.asGFloat() = GFloat(this)

fun Char.asGLetter() = GLetter(this)

/**
 * @throws IllegalArgumentException if the string is not a single letter
 */
fun String.asGLetter() =
    if (this.length == 1 && this[0].isLetter()) GLetter(this[0]) else throw IllegalArgumentException("'$this' is not a letter")

fun String.asGQuotedString() = GQuotedString(this)

fun String.asGUnquotedString() = GUnquotedString(this)

fun String.asGTailComment(key: String = ";") = GTailComment(this, key)

fun String.asGInlineComment(start: String = "(", end: String = ")") = GInlineComment(this, start, end)

fun String.asGRawExpression() = GRawExpression(this)

fun String.asGLineBreak() = GLineBreak(this)

fun String.asGUnknown() = GUnknown(this)

fun Char.asGUnknown() = GUnknown(this)
