/**
 * Parameter-word builders for the [G] DSL - one per letter GCODE_spec.md section 4.2 lists, plus
 * generic escape hatches for the letters it does not.
 *
 * The 21 letters are `X Y Z U V W A B C E F S P I J K D H L R Q`, and each appears in five shapes:
 *
 * ```
 * X(10)           // integer
 * X("10.50")      // any number, as its exact lexeme - see below
 * X(BigDecimal)   // a decimal already in hand
 * X(someGValue)   // anything else the model can hold
 * X               // a flag: the letter with no value (spec 3.2), as in G(28, X, Y) -> G28 X Y
 * ```
 *
 * **The `String` overload is a number, not text.** `X("10.50")` is the decimal 10.50 written with
 * the digits the caller chose, which is the only way to say `X10.50` rather than `X10.5` - a number
 * token's lexeme is part of its identity (see `GNumber`) and re-rendering from the value would
 * silently change the bytes, and with them the checksum. It rejects anything that is not a number
 * per section 3.1.
 *
 * For actual text use [text]; for an expression use [expr]:
 *
 * ```
 * S(text("Hello"))      // S"Hello"
 * S(expr("bed[0]"))     // S{bed[0]}
 * ```
 *
 * **The generic builders** - [word], [flag], [text], [bareString], [expr], [tailComment],
 * [inlineComment] and [number] - are for the letters section 4.2 does not list and for values built
 * elsewhere. Every per-letter helper below is one of them with the letter fixed, and carries no
 * documentation of its own for exactly that reason - read the shapes above.
 *
 * As elsewhere in this package, and unlike `code/core`, a bad call site throws
 * `IllegalArgumentException` rather than returning a value that carries the error: this input is
 * source code written by a programmer, not bytes off a wire.
 *
 * **`D` is the one letter with a real quirk.** It is a parameter here (`D(3)` -> `D3`, spec 4.2's
 * diameter and PID `D`), while the Marlin debug *command* of the same letter is `command('D', n)` in
 * `G.kt`. The parser reads both the same way, so this is the spec's own ambiguity, not a choice made
 * here - see `G.kt`'s `command(letter, number)` for the full letter-collision rule, which also covers
 * `T`.
 */
package org.qw3rtrun.p3d.g.code.dsl

import org.qw3rtrun.p3d.g.code.core.command.GCommentPart
import org.qw3rtrun.p3d.g.code.core.command.GFlagWord
import org.qw3rtrun.p3d.g.code.core.command.GParameterWord
import org.qw3rtrun.p3d.g.code.core.command.GUnnamedStr
import org.qw3rtrun.p3d.g.code.core.command.GWord
import org.qw3rtrun.p3d.g.code.core.token.*
import java.math.BigDecimal

val X: GWord = GFlagWord(GLetter('X'))

fun X(value: Int): GWord = word('X', value)

fun X(lexeme: String): GWord = word('X', lexeme)

fun X(value: BigDecimal): GWord = word('X', value)

fun X(value: GValue): GWord = word('X', value)

val Y: GWord = GFlagWord(GLetter('Y'))

fun Y(value: Int): GWord = word('Y', value)

fun Y(lexeme: String): GWord = word('Y', lexeme)

fun Y(value: BigDecimal): GWord = word('Y', value)

fun Y(value: GValue): GWord = word('Y', value)

val Z: GWord = GFlagWord(GLetter('Z'))

fun Z(value: Int): GWord = word('Z', value)

fun Z(lexeme: String): GWord = word('Z', lexeme)

fun Z(value: BigDecimal): GWord = word('Z', value)

fun Z(value: GValue): GWord = word('Z', value)

val U: GWord = GFlagWord(GLetter('U'))

fun U(value: Int): GWord = word('U', value)

fun U(lexeme: String): GWord = word('U', lexeme)

fun U(value: BigDecimal): GWord = word('U', value)

fun U(value: GValue): GWord = word('U', value)

val V: GWord = GFlagWord(GLetter('V'))

fun V(value: Int): GWord = word('V', value)

fun V(lexeme: String): GWord = word('V', lexeme)

fun V(value: BigDecimal): GWord = word('V', value)

fun V(value: GValue): GWord = word('V', value)

val W: GWord = GFlagWord(GLetter('W'))

fun W(value: Int): GWord = word('W', value)

fun W(lexeme: String): GWord = word('W', lexeme)

fun W(value: BigDecimal): GWord = word('W', value)

fun W(value: GValue): GWord = word('W', value)

val A: GWord = GFlagWord(GLetter('A'))

fun A(value: Int): GWord = word('A', value)

fun A(lexeme: String): GWord = word('A', lexeme)

fun A(value: BigDecimal): GWord = word('A', value)

fun A(value: GValue): GWord = word('A', value)

val B: GWord = GFlagWord(GLetter('B'))

fun B(value: Int): GWord = word('B', value)

fun B(lexeme: String): GWord = word('B', lexeme)

fun B(value: BigDecimal): GWord = word('B', value)

fun B(value: GValue): GWord = word('B', value)

val C: GWord = GFlagWord(GLetter('C'))

fun C(value: Int): GWord = word('C', value)

fun C(lexeme: String): GWord = word('C', lexeme)

fun C(value: BigDecimal): GWord = word('C', value)

fun C(value: GValue): GWord = word('C', value)

val E: GWord = GFlagWord(GLetter('E'))

fun E(value: Int): GWord = word('E', value)

fun E(lexeme: String): GWord = word('E', lexeme)

fun E(value: BigDecimal): GWord = word('E', value)

fun E(value: GValue): GWord = word('E', value)

val F: GWord = GFlagWord(GLetter('F'))

fun F(value: Int): GWord = word('F', value)

fun F(lexeme: String): GWord = word('F', lexeme)

fun F(value: BigDecimal): GWord = word('F', value)

fun F(value: GValue): GWord = word('F', value)

val S: GWord = GFlagWord(GLetter('S'))

fun S(value: Int): GWord = word('S', value)

fun S(lexeme: String): GWord = word('S', lexeme)

fun S(value: BigDecimal): GWord = word('S', value)

fun S(value: GValue): GWord = word('S', value)

val P: GWord = GFlagWord(GLetter('P'))

fun P(value: Int): GWord = word('P', value)

fun P(lexeme: String): GWord = word('P', lexeme)

fun P(value: BigDecimal): GWord = word('P', value)

fun P(value: GValue): GWord = word('P', value)

val I: GWord = GFlagWord(GLetter('I'))

fun I(value: Int): GWord = word('I', value)

fun I(lexeme: String): GWord = word('I', lexeme)

fun I(value: BigDecimal): GWord = word('I', value)

fun I(value: GValue): GWord = word('I', value)

val J: GWord = GFlagWord(GLetter('J'))

fun J(value: Int): GWord = word('J', value)

fun J(lexeme: String): GWord = word('J', lexeme)

fun J(value: BigDecimal): GWord = word('J', value)

fun J(value: GValue): GWord = word('J', value)

val K: GWord = GFlagWord(GLetter('K'))

fun K(value: Int): GWord = word('K', value)

fun K(lexeme: String): GWord = word('K', lexeme)

fun K(value: BigDecimal): GWord = word('K', value)

fun K(value: GValue): GWord = word('K', value)

val D: GWord = GFlagWord(GLetter('D'))

fun D(value: Int): GWord = word('D', value)

fun D(lexeme: String): GWord = word('D', lexeme)

fun D(value: BigDecimal): GWord = word('D', value)

fun D(value: GValue): GWord = word('D', value)

val H: GWord = GFlagWord(GLetter('H'))

fun H(value: Int): GWord = word('H', value)

fun H(lexeme: String): GWord = word('H', lexeme)

fun H(value: BigDecimal): GWord = word('H', value)

fun H(value: GValue): GWord = word('H', value)

val L: GWord = GFlagWord(GLetter('L'))

fun L(value: Int): GWord = word('L', value)

fun L(lexeme: String): GWord = word('L', lexeme)

fun L(value: BigDecimal): GWord = word('L', value)

fun L(value: GValue): GWord = word('L', value)

val R: GWord = GFlagWord(GLetter('R'))

fun R(value: Int): GWord = word('R', value)

fun R(lexeme: String): GWord = word('R', lexeme)

fun R(value: BigDecimal): GWord = word('R', value)

fun R(value: GValue): GWord = word('R', value)

val Q: GWord = GFlagWord(GLetter('Q'))

fun Q(value: Int): GWord = word('Q', value)

fun Q(lexeme: String): GWord = word('Q', lexeme)

fun Q(value: BigDecimal): GWord = word('Q', value)

fun Q(value: GValue): GWord = word('Q', value)

/**
 * `<letter><value>`, for any letter A-Z in either case. Throws `IllegalArgumentException` when
 * [letter] is not one (spec 4.3).
 *
 * ```
 * GEncoder.encode(G(1, word('Q', 5)))    // "G1 Q5"
 * GEncoder.encode(M(105, word('T', 1)))  // "M105 T1" - T as a parameter
 * ```
 */
fun word(letter: Char, value: Int): GWord = word(letter, GInt(value))

/**
 * `<letter><lexeme>`, where [lexeme] is a number written exactly as it should reach the wire.
 *
 * Rejects anything section 3.1 does not call a number. That is a programmer error - a caller writing
 * `X("abc")` has made a mistake at the call site, not received bad input - and this module throws
 * only for those.
 *
 * ```
 * GEncoder.encode(G(1, word('Q', "05")))   // "G1 Q05"
 * ```
 */
fun word(letter: Char, lexeme: String): GWord = word(letter, number(lexeme))

/**
 * `<letter><value>`, for a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, word('Q', BigDecimal("0.5"))))   // "G1 Q0.5"
 * ```
 */
fun word(letter: Char, value: BigDecimal): GWord = word(letter, GFloat(value))

/**
 * `<letter><value>`, the most general form: any value the model can hold.
 *
 * ```
 * GEncoder.encode(G(1, word('Q', expr("x"))))   // "G1 Q{x}"
 * GEncoder.encode(G(1, word('x', 10)))          // "G1 x10" - the case is kept
 * ```
 */
fun word(letter: Char, value: GValue): GWord = GParameterWord(identifier(letter), value)

/**
 * `<letter>` with no value: a flag (spec 3.2), as in the `X` and `Y` of `G28 X Y`. Throws
 * `IllegalArgumentException` when [letter] is not A-Z (spec 4.3).
 *
 * ```
 * GEncoder.encode(G(29, flag('T')))   // "G29 T"
 * ```
 */
fun flag(letter: Char): GWord = GFlagWord(identifier(letter))

/**
 * A quoted string value (spec 3.4): it renders between quotes, with inner quotes doubled.
 *
 * ```
 * text("Hi").rawText()             // "\"Hi\""
 * text("say \"hi\"").rawText()     // "\"say \"\"hi\"\"\""
 * ```
 */
fun text(value: String): GValue = GQuotedString(value)

/**
 * A bare rest-of-line string (spec 3.4a): the argument of `M117`, `M23`, `M118` and the handful of
 * other commands Marlin reads a `string_arg` for.
 *
 * It carries **no letter** - the text is the argument - so it is a word with an empty identifier
 * rather than a `word(letter, ...)`, and it renders undelimited: `M(117, bareString("Hello World"))`
 * is `M117 Hello World`.
 *
 * One rule the call site owns, because nothing below can check it: it goes **last** in a command,
 * since everything to the end of the line belongs to it. That is a property of the command being
 * built; what the string may contain is a property of the string, and is checked here.
 *
 * **It is the one value that can write a different command than the caller asked for**, and its
 * text is usually a filename or a message someone typed, so four characters are refused with the
 * position they were found at:
 *
 * - `\n` and `\r` end the line (spec 1.2), so `M30 a.gco\nM112` is a delete *and* an emergency
 *   stop, with the `N` word on one side of the break and the checksum on the other;
 * - `;` starts a comment (spec 3.4a, 6) that runs to the end of the line and swallows the checksum
 *   a transport appends behind it, so the block is rejected and resent forever;
 * - `*` is the checksum delimiter (spec 3.4a, 8.3), so the firmware reads part of the text as the
 *   checksum;
 * - anything outside printable ASCII `0x20`-`0x7E`, because that is the portable wire (spec 1.1).
 *
 * A refused string is not necessarily unsendable: spec 3.4b quoted strings are delimited, compose
 * with other parameters and may hold a `;`, so [text] is the way to say one where the command takes
 * a quoted argument. Length is **not** checked here - it depends on the whole block and on the line
 * number the transport will prepend, which `GLineLimit.payloadBudget` answers for the caller.
 *
 * ```
 * GEncoder.encode(M(117, bareString("Hello World")))   // "M117 Hello World"
 * GEncoder.encode(M(23, bareString("A LONG NAME.GCO")))   // "M23 A LONG NAME.GCO" - spaces are fine
 * bareString("a.gco;M112")                             // throws IllegalArgumentException
 * ```
 */
fun bareString(value: String): GWord {
    for (i in value.indices) {
        val c = value[i]
        val why = when {
            c == '\n' || c == '\r' ->
                "spec 1.2: it ends the line, so everything after it becomes a second command and " +
                    "the checksum lands on the wrong side of the break"
            c == ';' ->
                "spec 3.4a: it starts a comment, which swallows the checksum a transport appends"
            c == '*' ->
                "spec 8.3: it is the checksum delimiter, so the firmware would read what follows " +
                    "as the checksum"
            c < ' ' || c > '~' ->
                "spec 1.1: the wire carries printable ASCII, 0x20 to 0x7E, and this is outside it"
            else -> null
        }
        require(why == null) {
            val shown = if (c >= ' ' && c <= '~') "'$c'" else "U+%04X".format(c.code)
            "a bare rest-of-line string cannot contain $shown at position $i - $why. " +
                "Where the command takes a quoted argument, text(\"...\") can carry it (spec 3.4b)."
        }
    }
    return GUnnamedStr(GUnquotedString(value))
}

/**
 * An expression value (spec 3.5), wrapped in braces.
 *
 * ```
 * expr("bed[0]").rawText()   // "{bed[0]}"
 * ```
 */
fun expr(inner: String): GValue = GRawExpression("{" + inner + "}")

/**
 * A trailing comment (spec 6): `;<text>`, as a part of a block. A leading space is the caller's to
 * include.
 *
 * Returns the block layer's [GCommentPart], not the `GTailComment` token: a comment built here is
 * always going into a line, and the token itself is not a block part.
 *
 * ```
 * GEncoder.encode(block(tailComment(" x")))   // "; x"
 * ```
 */
fun tailComment(text: String): GCommentPart = GCommentPart(GTailComment(text))

/**
 * An inline comment (spec 6): `(<text>)`, as a part of a block.
 *
 * Returns the block layer's [GCommentPart], not the `GInlineComment` token, for the same reason as
 * [tailComment].
 *
 * ```
 * GEncoder.encode(block(G(28), inlineComment("x"), G(90)))   // "G28 (x) G90"
 * ```
 */
fun inlineComment(text: String): GCommentPart = GCommentPart(GInlineComment(text))

/**
 * A number value from its exact lexeme, per spec 3.1: optional sign, digits, optional single `.`.
 *
 * An integer becomes a [GInt] and anything with a decimal point a [GFloat], both keeping [lexeme]
 * verbatim so `X01`, `X+5`, `X.5` and `X1.` re-emit as written. The lexeme is validated by hand
 * rather than by `BigDecimal`'s own parser, which accepts more. Throws `IllegalArgumentException`
 * for an empty lexeme or anything else section 3.1 does not call a number.
 *
 * ```
 * number("01")    // GInt(1, "01")
 * number("1.")    // GFloat(BigDecimal("1."), "1.")
 * number("abc")   // throws IllegalArgumentException
 * ```
 */
fun number(lexeme: String): GNumber {
    require(lexeme.isNotEmpty()) { "a number cannot be empty" }
    var i = 0
    if (lexeme[0] == '+' || lexeme[0] == '-') i++
    var digits = 0
    var dots = 0
    while (i < lexeme.length) {
        val c = lexeme[i]
        when {
            c >= '0' && c <= '9' -> digits++
            c == '.' -> dots++
            else -> throw IllegalArgumentException("not a number (spec 3.1): '$lexeme'")
        }
        i++
    }
    require(digits > 0 && dots <= 1) { "not a number (spec 3.1): '$lexeme'" }
    return if (dots == 1) GFloat(BigDecimal(lexeme), lexeme) else GInt(lexeme.toInt(), lexeme)
}

/**
 * [letter] as an identifier - spec 4.3: only A-Z are identifiers, in either case (spec 2.2). Throws
 * `IllegalArgumentException` for anything else.
 *
 * ```
 * identifier('x')   // GLetter('x')
 * identifier('1')   // throws IllegalArgumentException
 * ```
 */
internal fun identifier(letter: Char): GLetter {
    require((letter >= 'A' && letter <= 'Z') || (letter >= 'a' && letter <= 'z')) {
        "spec 4.3: an identifier is a letter A-Z, got '$letter'"
    }
    return GLetter(letter)
}
