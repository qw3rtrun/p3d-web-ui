package org.qw3rtrun.p3d.g.code.core.token

import org.qw3rtrun.p3d.g.code.core.block.GBlockPart
import java.math.BigDecimal

/**
 * One lexical element of a G-code stream, as `GTokenizer` produces it (GCODE_spec.md sections 1 to 3).
 *
 * The closed set of token kinds is this file's sealed hierarchy: identifiers, values, comments,
 * separators, and `GUnknown` for input that is not lexically valid. **Round-trip fidelity is the
 * invariant**: concatenating [rawText] over a token stream reproduces its input byte for byte, which is
 * what lets a parse be diffed against a real capture.
 *
 * ```
 * GTokenizer.parse("G1 ;hi").joinToString("") { it.rawText() }   // "G1 ;hi"
 * ```
 */
sealed interface GToken {

    /**
     * The exact characters this token was read from, or that it will be written as.
     *
     * ```
     * GInt(1, "01").rawText()             // "01"
     * GTailComment(" move").rawText()     // "; move"
     * ```
     *
     * @return the token's bytes on the wire
     */
    fun rawText(): String
}

/**
 * A token that can stand as a field's value: a number, a string, or an expression (spec 3).
 *
 * ```
 * GTokenizer.parse("5").first() is GValue      // true
 * GTokenizer.parse("{x}").first() is GValue    // true
 * ```
 */
sealed interface GValue : GToken

/**
 * A value that is a literal - a number or a string - rather than an expression to evaluate.
 *
 * ```
 * GTokenizer.parse("\"a\"").first() is GLiteral   // true
 * GTokenizer.parse("{x}").first() is GLiteral     // false
 * ```
 */
sealed interface GLiteral : GValue

/**
 * A token that names a field: a letter, the checksum marker `*`, or the empty identifier of a bare
 * rest-of-line string.
 *
 * ```
 * GLetter('G').rawText()   // "G"
 * GChecksum.rawText()      // "*"
 * ```
 *
 * @property name the identifier's text, which is also its [rawText]
 */
sealed interface GIdentifier : GToken {
    val name: String
    override fun rawText() = name

    /**
     * Whether this identifier is the single letter [l], compared case-insensitively - spec 2.2: the
     * dialects are case-insensitive.
     *
     * Explicit ASCII folding rather than `lowercase()`/`uppercase()`, which were wrong three ways:
     * locale-dependent (a Turkish locale changes `i`/`I`), Unicode-wide - U+212A KELVIN SIGN
     * lowercases to `k`, so it matched `GLetter('k')` against spec 1.1's 7-bit wire format - and
     * they allocated two or three Strings on a path that runs for every element of every line.
     * Spec 1.1: the wire format is 7-bit ASCII, so `a`-`z` are the only characters that fold.
     * Anything else is compared unchanged - including a Unicode letter that happens to lowercase to
     * an ASCII one - and therefore compares unequal to every ASCII identifier.
     *
     * The name is wider than it says: `GChecksum.isLetter('*')` is true, because `*` folds to
     * itself. Preserved deliberately; renaming is queued as hygiene (todo 07).
     *
     * ```
     * GLetter('x').isLetter('X')   // true
     * GChecksum.isLetter('*')      // true
     * ```
     *
     * @param l the character to compare against, in either case
     * @return true when this identifier is exactly one character that folds to the same as [l]
     */
    fun isLetter(l: Char): Boolean = name.length == 1 && asciiFold(name[0]) == asciiFold(l)
}

private fun asciiFold(c: Char): Char = if (c >= 'a' && c <= 'z') (c.code - 32).toChar() else c

/**
 * Input that is not lexically valid - errors are values (spec 9): a stray character, a bare sign, a
 * malformed number, an unterminated string, comment or expression. It carries the offending
 * characters verbatim, so the stream keeps going past it and it still re-prints byte-identically.
 *
 * ```
 * GTokenizer.parse("1.2.3").toList()   // [GUnknown("1.2.3")]
 * GUnknown('?').rawText()              // "?"
 * ```
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
 * ```
 * GLetter('G').rawText()        // "G"
 * GLetter('x').isLetter('X')    // true
 * ```
 *
 * @property letter the letter as written
 */
data class GLetter(val letter: Char) : GIdentifier {
    override val name: String
        get() = letter.toString()
}

/**
 * The identifier of a field that has none: the bare rest-of-line string of spec 3.4a, as in
 * `M117 Hello World`. It renders as nothing, so a `GUnnamedStr` can carry an identifier like every
 * other word and still reach the wire as only its text.
 *
 * ```
 * GEmptyId.rawText()   // ""
 * ```
 */
object GEmptyId : GIdentifier {
    override val name: String
        get() = ""
}

/**
 * The checksum marker `*` (spec 8). The lexer produces it wherever a `*` appears outside a comment or
 * a string; the liner decides whether it is the line's checksum field.
 *
 * ```
 * GTokenizer.parse("*").toList()   // [GChecksum]
 * ```
 */
data object GChecksum : GIdentifier {
    override val name: String
        get() = "*"
}

/**
 * A comment (spec 6): a `;` comment to the end of the line, or a parenthesised one. Comments are not
 * fields, and they can also be placed in a `GBlock` being built.
 *
 * ```
 * (GTokenizer.parse(";hi").first() as GComment).string   // "hi"
 * ```
 *
 * @property string the comment's text, without its delimiters
 */
sealed interface GComment : GToken, GBlockPart {
    val string: String
}

/**
 * A comment that runs to the end of the line: [key] then [string]. A leading space is part of the
 * text, so `; move` and `;move` both round-trip as written.
 *
 * ```
 * GTailComment(" move").rawText()   // "; move"
 * ```
 *
 * @property key the marker the comment opens with
 */
data class GTailComment(override val string: String, val key: String = ";") : GComment {
    override fun rawText(): String = "$key$string"
}

/**
 * A parenthesised comment: [start], [string], [end]. Nested pairs inside it are part of the text.
 *
 * ```
 * GInlineComment("hi").rawText()   // "(hi)"
 * ```
 *
 * @property start the opening delimiter
 * @property end the closing delimiter
 */
data class GInlineComment(override val string: String, val start: String = "(", val end: String = ")") : GComment {
    override fun rawText(): String = "$start$string$end"
}

/**
 * A token that separates fields or lines rather than carrying content: whitespace or a line break.
 *
 * ```
 * GTokenizer.parse("G1 X1\n").filter { it is GSeparator }.toList()   // [GSpace, GLineBreak("\n")]
 * ```
 */
sealed interface GSeparator : GToken

/**
 * One whitespace character between fields - spec 2.1's space or tab - which a field may also contain
 * between its identifier and its value.
 *
 * ```
 * GTab.rawText()   // "\t"
 * ```
 *
 * @property char the whitespace character
 */
sealed interface GWhitespace : GSeparator {
    val char: Char
    override fun rawText(): String = char.toString()
}

/**
 * A single space.
 *
 * ```
 * GSpace.rawText()   // " "
 * ```
 */
data object GSpace : GWhitespace {
    override val char: Char = ' ';
}

/**
 * A single tab.
 *
 * ```
 * GTab.rawText()   // "\t"
 * ```
 */
data object GTab : GWhitespace {
    override val char: Char = '\t';
}

/**
 * A line terminator, spec 1.2: LF, or CRLF as one token. It prints as its class name in `toString`
 * so that a debug dump of a token list does not break across lines.
 *
 * ```
 * GLineBreak("\r\n").rawText()    // "\r\n"
 * GLineBreak("\r\n").toString()   // "GLineBreak"
 * ```
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
 * ```
 * GQuotedString("hi").string   // "hi"
 * ```
 *
 * @property string the decoded text, without delimiters or escapes
 */
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
 *
 * ```
 * GFloat("10.50").lexeme      // "10.50"
 * GInt(57, "057").rawText()   // "057"
 * ```
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
 * A quoted string, spec 3.4. It renders with its delimiters, and with every inner `"` doubled as
 * spec 3.4b escapes it.
 *
 * ```
 * GQuotedString("say \"hi\"").rawText()   // "\"say \"\"hi\"\"\""
 * ```
 */
data class GQuotedString(override val string: String) : GString {
    override fun rawText(): String = "\"${string.replace("\"", "\"\"")}\""
}

/**
 * A string that reaches the wire with no delimiters around it: spec 3.4a's bare rest-of-line string.
 *
 * This is a GCODE design gap, when some string parameters have no quote. The problem is it depends
 * only on the command's number itself, so it can be caught only when a semantic reveal - which is
 * why the token layer never produces one (`M117 Hello World` lexes letter by letter) and a decoder
 * that knows the command number builds it, through `GUnnamedStr`.
 *
 * [rawText] is the string itself, quotes included if it happens to contain any: there is no
 * delimiter to add and nothing to escape, because the value runs to the end of the line. Adding
 * quotes here would emit `M117 "Hi"` and put the quotes on the printer's display.
 *
 * ```
 * GUnquotedString("Hello World").rawText()   // "Hello World"
 * ```
 */
data class GUnquotedString(override val string: String) : GString {
    override fun rawText(): String = string
}

/**
 * An integer number. [lexeme] defaults to the canonical rendering of [int] and keeps whatever was
 * written otherwise, so `01` and `+5` round-trip.
 *
 * ```
 * GInt(57, "057").rawText()   // "057"
 * GInt(57, "057").int         // 57
 * ```
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
 * ```
 * GFloat("1.").rawText()                  // "1."
 * GFloat(BigDecimal("2.50")).lexeme       // "2.50"
 * GFloat(3).lexeme                        // "3"
 * ```
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
 * An expression value, spec 3.5: RS274/NGC's `[...]` or RepRapFirmware's `{...}`.
 *
 * [text] is the expression **as written, delimiters included** - nothing here evaluates one, and a
 * host that does not understand it still re-emits it byte for byte. The property was called
 * `exception` until it was read as one: in a module whose rule is that errors are values and the
 * core never throws, a token property of that name is the first thing a reader chasing a throw
 * site finds.
 *
 * ```
 * GTokenizer.parse("{bed[0]}").first()   // GRawExpression("{bed[0]}")
 * ```
 *
 * @property text the expression as written, delimiters included
 */
sealed interface GExpression : GValue {
    val text: String
}

/**
 * An expression kept as its raw text, as the lexer reads a brace expression; it re-emits unchanged.
 *
 * ```
 * GRawExpression("{bed[0]}").rawText()   // "{bed[0]}"
 * ```
 */
data class GRawExpression(override val text: String) : GExpression {
    override fun rawText(): String = text
}


/**
 * This integer as a number token, in its canonical rendering.
 *
 * ```
 * 5.toToken()   // GInt(5)
 * ```
 *
 * @receiver the value
 * @return a [GInt] whose lexeme is the canonical decimal
 */
fun Int.toToken() = GInt(this)

/**
 * This decimal as a number token, rendered through `BigDecimal.toString()`.
 *
 * ```
 * BigDecimal("1.5").toToken()   // GFloat(BigDecimal("1.5"), "1.5")
 * ```
 *
 * @receiver the value
 * @return a [GFloat] carrying the value and its string form
 */
fun BigDecimal.toToken() = GFloat(this)

/**
 * This character as a letter identifier. Nothing checks that it is a letter.
 *
 * ```
 * 'X'.toToken()   // GLetter('X')
 * ```
 *
 * @receiver the letter
 * @return a [GLetter]
 */
fun Char.toToken() = GLetter(this)

/**
 * This text as a quoted string value.
 *
 * ```
 * "hi".toToken()   // GQuotedString("hi"), which renders as "\"hi\""
 * ```
 *
 * @receiver the decoded text
 * @return a [GQuotedString]
 */
fun String.toToken() = GQuotedString(this)
