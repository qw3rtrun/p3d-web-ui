package org.qw3rtrun.p3d.g.code

import org.qw3rtrun.p3d.g.code.core.token.*
import java.math.BigDecimal

/**
 * Parameter-word builders for the [G] DSL - one per letter GCODE_spec.md section 4.2 lists, plus
 * generic escape hatches for the letters it does not.
 *
 * Each letter appears in five shapes:
 *
 * ```
 * X(10)           // integer
 * X("10.50")      // any number, as its exact lexeme - see below
 * X(BigDecimal)   // a decimal already in hand
 * X(someGValue)   // anything else the model can hold
 * X               // a flag: the letter with no value (spec 3.2)
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
 */
/** Flag `X` - the letter alone, no value (spec 3.2). */
val X: GWord = GFlagWord(GLetter('X'))

/** `X<value>`. */
fun X(value: Int): GWord = word('X', value)

/** `X<lexeme>` - the number written exactly as given. See the file KDoc. */
fun X(lexeme: String): GWord = word('X', lexeme)

/** `X<value>`. */
fun X(value: BigDecimal): GWord = word('X', value)

/** `X` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun X(value: GValue): GWord = word('X', value)

/** Flag `Y` - the letter alone, no value (spec 3.2). */
val Y: GWord = GFlagWord(GLetter('Y'))

/** `Y<value>`. */
fun Y(value: Int): GWord = word('Y', value)

/** `Y<lexeme>` - the number written exactly as given. See the file KDoc. */
fun Y(lexeme: String): GWord = word('Y', lexeme)

/** `Y<value>`. */
fun Y(value: BigDecimal): GWord = word('Y', value)

/** `Y` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun Y(value: GValue): GWord = word('Y', value)

/** Flag `Z` - the letter alone, no value (spec 3.2). */
val Z: GWord = GFlagWord(GLetter('Z'))

/** `Z<value>`. */
fun Z(value: Int): GWord = word('Z', value)

/** `Z<lexeme>` - the number written exactly as given. See the file KDoc. */
fun Z(lexeme: String): GWord = word('Z', lexeme)

/** `Z<value>`. */
fun Z(value: BigDecimal): GWord = word('Z', value)

/** `Z` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun Z(value: GValue): GWord = word('Z', value)

/** Flag `U` - the letter alone, no value (spec 3.2). */
val U: GWord = GFlagWord(GLetter('U'))

/** `U<value>`. */
fun U(value: Int): GWord = word('U', value)

/** `U<lexeme>` - the number written exactly as given. See the file KDoc. */
fun U(lexeme: String): GWord = word('U', lexeme)

/** `U<value>`. */
fun U(value: BigDecimal): GWord = word('U', value)

/** `U` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun U(value: GValue): GWord = word('U', value)

/** Flag `V` - the letter alone, no value (spec 3.2). */
val V: GWord = GFlagWord(GLetter('V'))

/** `V<value>`. */
fun V(value: Int): GWord = word('V', value)

/** `V<lexeme>` - the number written exactly as given. See the file KDoc. */
fun V(lexeme: String): GWord = word('V', lexeme)

/** `V<value>`. */
fun V(value: BigDecimal): GWord = word('V', value)

/** `V` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun V(value: GValue): GWord = word('V', value)

/** Flag `W` - the letter alone, no value (spec 3.2). */
val W: GWord = GFlagWord(GLetter('W'))

/** `W<value>`. */
fun W(value: Int): GWord = word('W', value)

/** `W<lexeme>` - the number written exactly as given. See the file KDoc. */
fun W(lexeme: String): GWord = word('W', lexeme)

/** `W<value>`. */
fun W(value: BigDecimal): GWord = word('W', value)

/** `W` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun W(value: GValue): GWord = word('W', value)

/** Flag `A` - the letter alone, no value (spec 3.2). */
val A: GWord = GFlagWord(GLetter('A'))

/** `A<value>`. */
fun A(value: Int): GWord = word('A', value)

/** `A<lexeme>` - the number written exactly as given. See the file KDoc. */
fun A(lexeme: String): GWord = word('A', lexeme)

/** `A<value>`. */
fun A(value: BigDecimal): GWord = word('A', value)

/** `A` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun A(value: GValue): GWord = word('A', value)

/** Flag `B` - the letter alone, no value (spec 3.2). */
val B: GWord = GFlagWord(GLetter('B'))

/** `B<value>`. */
fun B(value: Int): GWord = word('B', value)

/** `B<lexeme>` - the number written exactly as given. See the file KDoc. */
fun B(lexeme: String): GWord = word('B', lexeme)

/** `B<value>`. */
fun B(value: BigDecimal): GWord = word('B', value)

/** `B` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun B(value: GValue): GWord = word('B', value)

/** Flag `C` - the letter alone, no value (spec 3.2). */
val C: GWord = GFlagWord(GLetter('C'))

/** `C<value>`. */
fun C(value: Int): GWord = word('C', value)

/** `C<lexeme>` - the number written exactly as given. See the file KDoc. */
fun C(lexeme: String): GWord = word('C', lexeme)

/** `C<value>`. */
fun C(value: BigDecimal): GWord = word('C', value)

/** `C` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun C(value: GValue): GWord = word('C', value)

/** Flag `E` - the letter alone, no value (spec 3.2). */
val E: GWord = GFlagWord(GLetter('E'))

/** `E<value>`. */
fun E(value: Int): GWord = word('E', value)

/** `E<lexeme>` - the number written exactly as given. See the file KDoc. */
fun E(lexeme: String): GWord = word('E', lexeme)

/** `E<value>`. */
fun E(value: BigDecimal): GWord = word('E', value)

/** `E` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun E(value: GValue): GWord = word('E', value)

/** Flag `F` - the letter alone, no value (spec 3.2). */
val F: GWord = GFlagWord(GLetter('F'))

/** `F<value>`. */
fun F(value: Int): GWord = word('F', value)

/** `F<lexeme>` - the number written exactly as given. See the file KDoc. */
fun F(lexeme: String): GWord = word('F', lexeme)

/** `F<value>`. */
fun F(value: BigDecimal): GWord = word('F', value)

/** `F` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun F(value: GValue): GWord = word('F', value)

/** Flag `S` - the letter alone, no value (spec 3.2). */
val S: GWord = GFlagWord(GLetter('S'))

/** `S<value>`. */
fun S(value: Int): GWord = word('S', value)

/** `S<lexeme>` - the number written exactly as given. See the file KDoc. */
fun S(lexeme: String): GWord = word('S', lexeme)

/** `S<value>`. */
fun S(value: BigDecimal): GWord = word('S', value)

/** `S` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun S(value: GValue): GWord = word('S', value)

/** Flag `P` - the letter alone, no value (spec 3.2). */
val P: GWord = GFlagWord(GLetter('P'))

/** `P<value>`. */
fun P(value: Int): GWord = word('P', value)

/** `P<lexeme>` - the number written exactly as given. See the file KDoc. */
fun P(lexeme: String): GWord = word('P', lexeme)

/** `P<value>`. */
fun P(value: BigDecimal): GWord = word('P', value)

/** `P` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun P(value: GValue): GWord = word('P', value)

/** Flag `I` - the letter alone, no value (spec 3.2). */
val I: GWord = GFlagWord(GLetter('I'))

/** `I<value>`. */
fun I(value: Int): GWord = word('I', value)

/** `I<lexeme>` - the number written exactly as given. See the file KDoc. */
fun I(lexeme: String): GWord = word('I', lexeme)

/** `I<value>`. */
fun I(value: BigDecimal): GWord = word('I', value)

/** `I` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun I(value: GValue): GWord = word('I', value)

/** Flag `J` - the letter alone, no value (spec 3.2). */
val J: GWord = GFlagWord(GLetter('J'))

/** `J<value>`. */
fun J(value: Int): GWord = word('J', value)

/** `J<lexeme>` - the number written exactly as given. See the file KDoc. */
fun J(lexeme: String): GWord = word('J', lexeme)

/** `J<value>`. */
fun J(value: BigDecimal): GWord = word('J', value)

/** `J` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun J(value: GValue): GWord = word('J', value)

/** Flag `K` - the letter alone, no value (spec 3.2). */
val K: GWord = GFlagWord(GLetter('K'))

/** `K<value>`. */
fun K(value: Int): GWord = word('K', value)

/** `K<lexeme>` - the number written exactly as given. See the file KDoc. */
fun K(lexeme: String): GWord = word('K', lexeme)

/** `K<value>`. */
fun K(value: BigDecimal): GWord = word('K', value)

/** `K` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun K(value: GValue): GWord = word('K', value)

/** Flag `D` - the letter alone, no value (spec 3.2). */
val D: GWord = GFlagWord(GLetter('D'))

/** `D<value>`. */
fun D(value: Int): GWord = word('D', value)

/** `D<lexeme>` - the number written exactly as given. See the file KDoc. */
fun D(lexeme: String): GWord = word('D', lexeme)

/** `D<value>`. */
fun D(value: BigDecimal): GWord = word('D', value)

/** `D` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun D(value: GValue): GWord = word('D', value)

/** Flag `H` - the letter alone, no value (spec 3.2). */
val H: GWord = GFlagWord(GLetter('H'))

/** `H<value>`. */
fun H(value: Int): GWord = word('H', value)

/** `H<lexeme>` - the number written exactly as given. See the file KDoc. */
fun H(lexeme: String): GWord = word('H', lexeme)

/** `H<value>`. */
fun H(value: BigDecimal): GWord = word('H', value)

/** `H` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun H(value: GValue): GWord = word('H', value)

/** Flag `L` - the letter alone, no value (spec 3.2). */
val L: GWord = GFlagWord(GLetter('L'))

/** `L<value>`. */
fun L(value: Int): GWord = word('L', value)

/** `L<lexeme>` - the number written exactly as given. See the file KDoc. */
fun L(lexeme: String): GWord = word('L', lexeme)

/** `L<value>`. */
fun L(value: BigDecimal): GWord = word('L', value)

/** `L` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun L(value: GValue): GWord = word('L', value)

/** Flag `R` - the letter alone, no value (spec 3.2). */
val R: GWord = GFlagWord(GLetter('R'))

/** `R<value>`. */
fun R(value: Int): GWord = word('R', value)

/** `R<lexeme>` - the number written exactly as given. See the file KDoc. */
fun R(lexeme: String): GWord = word('R', lexeme)

/** `R<value>`. */
fun R(value: BigDecimal): GWord = word('R', value)

/** `R` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun R(value: GValue): GWord = word('R', value)

/** Flag `Q` - the letter alone, no value (spec 3.2). */
val Q: GWord = GFlagWord(GLetter('Q'))

/** `Q<value>`. */
fun Q(value: Int): GWord = word('Q', value)

/** `Q<lexeme>` - the number written exactly as given. See the file KDoc. */
fun Q(lexeme: String): GWord = word('Q', lexeme)

/** `Q<value>`. */
fun Q(value: BigDecimal): GWord = word('Q', value)

/** `Q` carrying any value the model can hold - a string, an expression, a prebuilt token. */
fun Q(value: GValue): GWord = word('Q', value)

// ---------------------------------------------------------------------------
// Generic builders, for the letters section 4.2 does not list and for values
// built elsewhere. Every helper above is one of these with the letter fixed.
// ---------------------------------------------------------------------------

/** `<letter><value>`, for any letter A-Z. */
fun word(letter: Char, value: Int): GWord = word(letter, GInt(value))

/**
 * `<letter><lexeme>`, where [lexeme] is a number written exactly as it should reach the wire.
 *
 * Rejects anything section 3.1 does not call a number. That is a programmer error - a caller writing
 * `X("abc")` has made a mistake at the call site, not received bad input - and this module throws
 * only for those.
 */
fun word(letter: Char, lexeme: String): GWord = word(letter, number(lexeme))

/** `<letter><value>`. */
fun word(letter: Char, value: BigDecimal): GWord = word(letter, GFloat(value))

/** `<letter><value>`, the most general form. */
fun word(letter: Char, value: GValue): GWord = GParameterWord(identifier(letter), value)

/** `<letter>` with no value: a flag (spec 3.2), as in the `X` and `Y` of `G28 X Y`. */
fun flag(letter: Char): GWord = GFlagWord(identifier(letter))

/** A quoted string value (spec 3.4): `text("Hi")` renders as `"Hi"`, with inner quotes doubled. */
fun text(value: String): GValue = GQuotedString(value)

/** An expression value (spec 3.5): `expr("bed[0]")` renders as `{bed[0]}`. */
fun expr(inner: String): GValue = GRawExpression("{" + inner + "}")

/** A trailing comment (spec 6): `;<text>`. A leading space is the caller's to include. */
fun tailComment(text: String): GComment = GTailComment(text)

/** An inline comment (spec 6): `(<text>)`. */
fun inlineComment(text: String): GComment = GInlineComment(text)

/**
 * A number value from its exact lexeme, per spec 3.1: optional sign, digits, optional single `.`.
 *
 * An integer becomes a [GInt] and anything with a decimal point a [GFloat], both keeping [lexeme]
 * verbatim so `X01`, `X+5`, `X.5` and `X1.` re-emit as written.
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

/** spec 4.3: only A-Z are identifiers, in either case (spec 2.2). */
internal fun identifier(letter: Char): GLetter {
    require((letter >= 'A' && letter <= 'Z') || (letter >= 'a' && letter <= 'z')) {
        "spec 4.3: an identifier is a letter A-Z, got '$letter'"
    }
    return GLetter(letter)
}
