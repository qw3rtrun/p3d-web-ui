package org.qw3rtrun.p3d.g.code.core.token

import java.math.BigDecimal

sealed interface GToken {
    fun rawText(): String
    fun toSeq() = sequenceOf(this)
}

sealed interface GValue : GToken {
    override fun toSeq(): Sequence<GValue> = sequenceOf(this)
}

sealed interface GLiteral : GValue

sealed interface GIdentifier : GToken {
    val name: String
    override fun rawText() = name

    /**
     * spec 2.2: the dialects are case-insensitive.
     *
     * Explicit ASCII folding rather than `lowercase()`/`uppercase()`, which were wrong three ways:
     * locale-dependent (a Turkish locale changes `i`/`I`), Unicode-wide - U+212A KELVIN SIGN
     * lowercases to `k`, so it matched `GLetter('k')` against spec 1.1's 7-bit wire format - and
     * they allocated two or three Strings on a path that runs for every element of every line.
     *
     * The name is wider than it says: `GChecksum.isLetter('*')` is true, because `*` folds to
     * itself. Preserved deliberately; renaming is queued as hygiene (todo 07).
     */
    fun isLetter(l: Char): Boolean = name.length == 1 && asciiFold(name[0]) == asciiFold(l)
}

/**
 * spec 1.1: the wire format is 7-bit ASCII, so `a`-`z` are the only characters that fold. Anything
 * else is returned unchanged - including a Unicode letter that happens to lowercase to an ASCII one -
 * and therefore compares unequal to every ASCII identifier.
 */
private fun asciiFold(c: Char): Char = if (c >= 'a' && c <= 'z') (c.code - 32).toChar() else c

data class GUnknown(val str: String) : GToken {
    constructor(ch: Char) : this(ch.toString())

    override fun rawText(): String = str
}

data class GLetter(val letter: Char) : GIdentifier {
    override val name: String
        get() = letter.toString()
}

data object GChecksum : GIdentifier {
    override val name: String
        get() = "*"
}

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

data object GTab : GWhitespace {
    override val char: Char = '\t';
}

data class GLineBreak(val breaker: String = "\n") : GSeparator {
    override fun toString(): String = this.javaClass.simpleName
    override fun rawText(): String = breaker
}

sealed interface GString : GLiteral {
    val string: String
}

/**
 * A numeric value, spec section 3.1.
 *
 * A number token carries both its parsed [number] and the [lexeme] it was read from, because the two
 * are not interchangeable: `01`, `+5`, `.5` and `1.` are all valid input whose canonical rendering
 * differs from what was written. [rawText] returns the lexeme, so the token stream reproduces its
 * input byte for byte; the lexeme is part of token identity for the same reason.
 *
 * **The parsed value of a decimal is a `java.math.BigDecimal`, and that is a deliberate, recorded
 * exception to this module's dependency-free rule** (todo 02). Arbitrary-precision decimal exists in
 * none of the port targets - JS/TS, C, Rust - without a library, so a port cannot carry this type
 * across and has to choose its own replacement. Two things make that cost affordable and bounded:
 *
 * - Nothing in this module reads the value. [rawText] reads the [lexeme], so round-tripping - the
 *   invariant the whole token layer is built on - never touches `BigDecimal`. A port that drops the
 *   parsed value entirely still lexes and re-emits correctly.
 * - `BigDecimal` is exact for every decimal a G-code file can contain, and its scale is what makes
 *   `1.0` and `1.00` distinct tokens. A port replacing it wants the same property: a scaled integer
 *   pair (`mantissa x 10^-scale`) is the portable equivalent, not a binary float.
 *
 * The alternative - dropping the parsed value, or storing mantissa and scale as two `Int`s - was
 * considered and deferred. It buys portability the module cannot yet spend, at the cost of a public
 * shape change across every caller. Spec Appendix B states the same liability for a reader who never
 * opens this file.
 *
 * There is deliberately **no `Double` entry point**. A `Double` cannot hold most authored decimals,
 * and unlike every other construction path it gives the caller no way to state the lexeme the value
 * should render as: `(0.1 + 0.2)` renders as `0.30000000000000004`, 19 characters against the <= 76
 * payload budget (spec section 1.3) and against the 3-5 decimals section 3.1 asks generators to round
 * to. That rounding is a call-site decision. A caller holding a `Double` passes a [String] or rounds
 * explicitly.
 */
sealed interface GNumber : GLiteral {
    val number: Number

    /** The exact characters this number was lexed from, sign and non-canonical digits included. */
    val lexeme: String

    override fun rawText(): String = lexeme
}

data class GQuotedString(override val string: String) : GString {
    override fun rawText(): String = "\"${string.replace("\"", "\"\"")}\""
}

// This is a GCODE design gap, when some string parameters have no quote.
// The problem is it depends only on the command's number itself, so it can be caught only when a semantic reveal
data class GUnquotedString(override val string: String) : GString {
    override fun rawText(): String = "\"${string.replace("\"", "\"\"")}\""
}

data class GInt(val int: Int, override val lexeme: String = int.toString()) : GNumber {
    override val number: Number
        get() = int
}

data class GFloat(val value: BigDecimal, override val lexeme: String = value.toString()) : GNumber {
    constructor(string: String) : this(BigDecimal(string), string)
    constructor(int: Int) : this(BigDecimal(int))

    override val number: Number
        get() = value
}

sealed interface GExpression : GValue {
    val exception: String
}

data class GRawExpression(override val exception: String) : GExpression {
    override fun rawText(): String = exception
}


fun Int.toToken() = GInt(this)
fun BigDecimal.toToken() = GFloat(this)
fun Char.toToken() = GLetter(this)
fun String.toToken() = GQuotedString(this)
