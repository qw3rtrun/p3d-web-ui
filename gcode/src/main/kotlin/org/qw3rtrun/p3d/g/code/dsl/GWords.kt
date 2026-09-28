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
 * elsewhere. Every per-letter helper is one of them with the letter fixed.
 */
package org.qw3rtrun.p3d.g.code.dsl

import org.qw3rtrun.p3d.g.code.core.block.GFlagWord
import org.qw3rtrun.p3d.g.code.core.block.GParameterWord
import org.qw3rtrun.p3d.g.code.core.block.GUnnamedStr
import org.qw3rtrun.p3d.g.code.core.block.GWord
import org.qw3rtrun.p3d.g.code.core.token.*
import java.math.BigDecimal

val X: GWord = GFlagWord(GLetter('X'))

/**
 * `X<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, X(10)))   // "G1 X10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun X(value: Int): GWord = word('X', value)

/**
 * `X<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, X("10.50")))   // "G1 X10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun X(lexeme: String): GWord = word('X', lexeme)

/**
 * `X<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, X(BigDecimal("10.5"))))   // "G1 X10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun X(value: BigDecimal): GWord = word('X', value)

/**
 * `X` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, X(expr("x0"))))   // "G1 X{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun X(value: GValue): GWord = word('X', value)

val Y: GWord = GFlagWord(GLetter('Y'))

/**
 * `Y<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, Y(10)))   // "G1 Y10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun Y(value: Int): GWord = word('Y', value)

/**
 * `Y<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, Y("10.50")))   // "G1 Y10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun Y(lexeme: String): GWord = word('Y', lexeme)

/**
 * `Y<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, Y(BigDecimal("10.5"))))   // "G1 Y10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun Y(value: BigDecimal): GWord = word('Y', value)

/**
 * `Y` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, Y(expr("x0"))))   // "G1 Y{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun Y(value: GValue): GWord = word('Y', value)

val Z: GWord = GFlagWord(GLetter('Z'))

/**
 * `Z<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, Z(10)))   // "G1 Z10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun Z(value: Int): GWord = word('Z', value)

/**
 * `Z<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, Z("10.50")))   // "G1 Z10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun Z(lexeme: String): GWord = word('Z', lexeme)

/**
 * `Z<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, Z(BigDecimal("10.5"))))   // "G1 Z10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun Z(value: BigDecimal): GWord = word('Z', value)

/**
 * `Z` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, Z(expr("x0"))))   // "G1 Z{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun Z(value: GValue): GWord = word('Z', value)

val U: GWord = GFlagWord(GLetter('U'))

/**
 * `U<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, U(10)))   // "G1 U10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun U(value: Int): GWord = word('U', value)

/**
 * `U<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, U("10.50")))   // "G1 U10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun U(lexeme: String): GWord = word('U', lexeme)

/**
 * `U<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, U(BigDecimal("10.5"))))   // "G1 U10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun U(value: BigDecimal): GWord = word('U', value)

/**
 * `U` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, U(expr("x0"))))   // "G1 U{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun U(value: GValue): GWord = word('U', value)

val V: GWord = GFlagWord(GLetter('V'))

/**
 * `V<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, V(10)))   // "G1 V10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun V(value: Int): GWord = word('V', value)

/**
 * `V<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, V("10.50")))   // "G1 V10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun V(lexeme: String): GWord = word('V', lexeme)

/**
 * `V<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, V(BigDecimal("10.5"))))   // "G1 V10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun V(value: BigDecimal): GWord = word('V', value)

/**
 * `V` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, V(expr("x0"))))   // "G1 V{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun V(value: GValue): GWord = word('V', value)

val W: GWord = GFlagWord(GLetter('W'))

/**
 * `W<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, W(10)))   // "G1 W10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun W(value: Int): GWord = word('W', value)

/**
 * `W<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, W("10.50")))   // "G1 W10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun W(lexeme: String): GWord = word('W', lexeme)

/**
 * `W<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, W(BigDecimal("10.5"))))   // "G1 W10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun W(value: BigDecimal): GWord = word('W', value)

/**
 * `W` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, W(expr("x0"))))   // "G1 W{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun W(value: GValue): GWord = word('W', value)

val A: GWord = GFlagWord(GLetter('A'))

/**
 * `A<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, A(10)))   // "G1 A10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun A(value: Int): GWord = word('A', value)

/**
 * `A<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, A("10.50")))   // "G1 A10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun A(lexeme: String): GWord = word('A', lexeme)

/**
 * `A<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, A(BigDecimal("10.5"))))   // "G1 A10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun A(value: BigDecimal): GWord = word('A', value)

/**
 * `A` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, A(expr("x0"))))   // "G1 A{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun A(value: GValue): GWord = word('A', value)

val B: GWord = GFlagWord(GLetter('B'))

/**
 * `B<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, B(10)))   // "G1 B10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun B(value: Int): GWord = word('B', value)

/**
 * `B<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, B("10.50")))   // "G1 B10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun B(lexeme: String): GWord = word('B', lexeme)

/**
 * `B<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, B(BigDecimal("10.5"))))   // "G1 B10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun B(value: BigDecimal): GWord = word('B', value)

/**
 * `B` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, B(expr("x0"))))   // "G1 B{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun B(value: GValue): GWord = word('B', value)

val C: GWord = GFlagWord(GLetter('C'))

/**
 * `C<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, C(10)))   // "G1 C10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun C(value: Int): GWord = word('C', value)

/**
 * `C<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, C("10.50")))   // "G1 C10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun C(lexeme: String): GWord = word('C', lexeme)

/**
 * `C<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, C(BigDecimal("10.5"))))   // "G1 C10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun C(value: BigDecimal): GWord = word('C', value)

/**
 * `C` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, C(expr("x0"))))   // "G1 C{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun C(value: GValue): GWord = word('C', value)

val E: GWord = GFlagWord(GLetter('E'))

/**
 * `E<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, E(10)))   // "G1 E10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun E(value: Int): GWord = word('E', value)

/**
 * `E<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, E("10.50")))   // "G1 E10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun E(lexeme: String): GWord = word('E', lexeme)

/**
 * `E<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, E(BigDecimal("10.5"))))   // "G1 E10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun E(value: BigDecimal): GWord = word('E', value)

/**
 * `E` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, E(expr("x0"))))   // "G1 E{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun E(value: GValue): GWord = word('E', value)

val F: GWord = GFlagWord(GLetter('F'))

/**
 * `F<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, F(10)))   // "G1 F10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun F(value: Int): GWord = word('F', value)

/**
 * `F<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, F("10.50")))   // "G1 F10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun F(lexeme: String): GWord = word('F', lexeme)

/**
 * `F<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, F(BigDecimal("10.5"))))   // "G1 F10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun F(value: BigDecimal): GWord = word('F', value)

/**
 * `F` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, F(expr("x0"))))   // "G1 F{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun F(value: GValue): GWord = word('F', value)

val S: GWord = GFlagWord(GLetter('S'))

/**
 * `S<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, S(10)))   // "G1 S10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun S(value: Int): GWord = word('S', value)

/**
 * `S<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, S("10.50")))   // "G1 S10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun S(lexeme: String): GWord = word('S', lexeme)

/**
 * `S<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, S(BigDecimal("10.5"))))   // "G1 S10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun S(value: BigDecimal): GWord = word('S', value)

/**
 * `S` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, S(expr("x0"))))   // "G1 S{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun S(value: GValue): GWord = word('S', value)

val P: GWord = GFlagWord(GLetter('P'))

/**
 * `P<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, P(10)))   // "G1 P10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun P(value: Int): GWord = word('P', value)

/**
 * `P<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, P("10.50")))   // "G1 P10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun P(lexeme: String): GWord = word('P', lexeme)

/**
 * `P<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, P(BigDecimal("10.5"))))   // "G1 P10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun P(value: BigDecimal): GWord = word('P', value)

/**
 * `P` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, P(expr("x0"))))   // "G1 P{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun P(value: GValue): GWord = word('P', value)

val I: GWord = GFlagWord(GLetter('I'))

/**
 * `I<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, I(10)))   // "G1 I10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun I(value: Int): GWord = word('I', value)

/**
 * `I<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, I("10.50")))   // "G1 I10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun I(lexeme: String): GWord = word('I', lexeme)

/**
 * `I<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, I(BigDecimal("10.5"))))   // "G1 I10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun I(value: BigDecimal): GWord = word('I', value)

/**
 * `I` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, I(expr("x0"))))   // "G1 I{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun I(value: GValue): GWord = word('I', value)

val J: GWord = GFlagWord(GLetter('J'))

/**
 * `J<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, J(10)))   // "G1 J10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun J(value: Int): GWord = word('J', value)

/**
 * `J<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, J("10.50")))   // "G1 J10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun J(lexeme: String): GWord = word('J', lexeme)

/**
 * `J<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, J(BigDecimal("10.5"))))   // "G1 J10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun J(value: BigDecimal): GWord = word('J', value)

/**
 * `J` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, J(expr("x0"))))   // "G1 J{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun J(value: GValue): GWord = word('J', value)

val K: GWord = GFlagWord(GLetter('K'))

/**
 * `K<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, K(10)))   // "G1 K10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun K(value: Int): GWord = word('K', value)

/**
 * `K<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, K("10.50")))   // "G1 K10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun K(lexeme: String): GWord = word('K', lexeme)

/**
 * `K<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, K(BigDecimal("10.5"))))   // "G1 K10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun K(value: BigDecimal): GWord = word('K', value)

/**
 * `K` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, K(expr("x0"))))   // "G1 K{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun K(value: GValue): GWord = word('K', value)

val D: GWord = GFlagWord(GLetter('D'))

/**
 * `D<value>` - the letter with an integer value. `D` is a parameter here; the Marlin debug
 * command is `command('D', n)`.
 *
 * ```
 * GEncoder.encode(G(1, D(10)))   // "G1 D10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun D(value: Int): GWord = word('D', value)

/**
 * `D<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, D("10.50")))   // "G1 D10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun D(lexeme: String): GWord = word('D', lexeme)

/**
 * `D<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, D(BigDecimal("10.5"))))   // "G1 D10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun D(value: BigDecimal): GWord = word('D', value)

/**
 * `D` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, D(expr("x0"))))   // "G1 D{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun D(value: GValue): GWord = word('D', value)

val H: GWord = GFlagWord(GLetter('H'))

/**
 * `H<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, H(10)))   // "G1 H10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun H(value: Int): GWord = word('H', value)

/**
 * `H<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, H("10.50")))   // "G1 H10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun H(lexeme: String): GWord = word('H', lexeme)

/**
 * `H<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, H(BigDecimal("10.5"))))   // "G1 H10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun H(value: BigDecimal): GWord = word('H', value)

/**
 * `H` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, H(expr("x0"))))   // "G1 H{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun H(value: GValue): GWord = word('H', value)

val L: GWord = GFlagWord(GLetter('L'))

/**
 * `L<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, L(10)))   // "G1 L10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun L(value: Int): GWord = word('L', value)

/**
 * `L<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, L("10.50")))   // "G1 L10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun L(lexeme: String): GWord = word('L', lexeme)

/**
 * `L<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, L(BigDecimal("10.5"))))   // "G1 L10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun L(value: BigDecimal): GWord = word('L', value)

/**
 * `L` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, L(expr("x0"))))   // "G1 L{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun L(value: GValue): GWord = word('L', value)

val R: GWord = GFlagWord(GLetter('R'))

/**
 * `R<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, R(10)))   // "G1 R10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun R(value: Int): GWord = word('R', value)

/**
 * `R<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, R("10.50")))   // "G1 R10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun R(lexeme: String): GWord = word('R', lexeme)

/**
 * `R<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, R(BigDecimal("10.5"))))   // "G1 R10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun R(value: BigDecimal): GWord = word('R', value)

/**
 * `R` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, R(expr("x0"))))   // "G1 R{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun R(value: GValue): GWord = word('R', value)

val Q: GWord = GFlagWord(GLetter('Q'))

/**
 * `Q<value>` - the letter with an integer value.
 *
 * ```
 * GEncoder.encode(G(1, Q(10)))   // "G1 Q10"
 * ```
 *
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
 */
fun Q(value: Int): GWord = word('Q', value)

/**
 * `Q<lexeme>` - the number written exactly as given, see the file KDoc. Throws
 * `IllegalArgumentException` when [lexeme] is not a number (spec 3.1).
 *
 * ```
 * GEncoder.encode(G(1, Q("10.50")))   // "G1 Q10.50"
 * ```
 *
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun Q(lexeme: String): GWord = word('Q', lexeme)

/**
 * `Q<value>` - the letter with a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, Q(BigDecimal("10.5"))))   // "G1 Q10.5"
 * ```
 *
 * @param value the decimal
 * @return the parameter word
 */
fun Q(value: BigDecimal): GWord = word('Q', value)

/**
 * `Q` carrying any value the model can hold - a string, an expression, a prebuilt token.
 *
 * ```
 * GEncoder.encode(G(1, Q(expr("x0"))))   // "G1 Q{x0}"
 * ```
 *
 * @param value the value token
 * @return the parameter word
 */
fun Q(value: GValue): GWord = word('Q', value)

/**
 * `<letter><value>`, for any letter A-Z in either case. Throws `IllegalArgumentException` when
 * [letter] is not one (spec 4.3).
 *
 * ```
 * GEncoder.encode(G(1, word('Q', 5)))    // "G1 Q5"
 * GEncoder.encode(M(105, word('T', 1)))  // "M105 T1" - T as a parameter
 * ```
 *
 * @param letter the parameter letter
 * @param value the integer, rendered in canonical decimal
 * @return the parameter word
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
 *
 * @param letter the parameter letter, A-Z in either case
 * @param lexeme the number's exact characters
 * @return the parameter word
 */
fun word(letter: Char, lexeme: String): GWord = word(letter, number(lexeme))

/**
 * `<letter><value>`, for a decimal already in hand, rendered through `BigDecimal.toString()`.
 *
 * ```
 * GEncoder.encode(G(1, word('Q', BigDecimal("0.5"))))   // "G1 Q0.5"
 * ```
 *
 * @param letter the parameter letter, A-Z in either case
 * @param value the decimal
 * @return the parameter word
 */
fun word(letter: Char, value: BigDecimal): GWord = word(letter, GFloat(value))

/**
 * `<letter><value>`, the most general form: any value the model can hold.
 *
 * ```
 * GEncoder.encode(G(1, word('Q', expr("x"))))   // "G1 Q{x}"
 * GEncoder.encode(G(1, word('x', 10)))          // "G1 x10" - the case is kept
 * ```
 *
 * @param letter the parameter letter, A-Z in either case
 * @param value the value token
 * @return the parameter word
 */
fun word(letter: Char, value: GValue): GWord = GParameterWord(identifier(letter), value)

/**
 * `<letter>` with no value: a flag (spec 3.2), as in the `X` and `Y` of `G28 X Y`. Throws
 * `IllegalArgumentException` when [letter] is not A-Z (spec 4.3).
 *
 * ```
 * GEncoder.encode(G(29, flag('T')))   // "G29 T"
 * ```
 *
 * @param letter the parameter letter, A-Z in either case
 * @return the flag word
 */
fun flag(letter: Char): GWord = GFlagWord(identifier(letter))

/**
 * A quoted string value (spec 3.4): it renders between quotes, with inner quotes doubled.
 *
 * ```
 * text("Hi").rawText()             // "\"Hi\""
 * text("say \"hi\"").rawText()     // "\"say \"\"hi\"\"\""
 * ```
 *
 * @param value the text, unescaped
 * @return the quoted string value
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
 * Two rules the call site owns, because nothing below can check them: it goes **last** in a
 * command, since everything to the end of the line belongs to it, and it cannot contain `;`, which
 * every parser reads as the start of a comment (spec 3.4a).
 *
 * ```
 * GEncoder.encode(M(117, bareString("Hello World")))   // "M117 Hello World"
 * ```
 *
 * @param value the text, to the end of the line
 * @return the letterless word carrying it
 */
fun bareString(value: String): GWord = GUnnamedStr(GUnquotedString(value))

/**
 * An expression value (spec 3.5), wrapped in braces.
 *
 * ```
 * expr("bed[0]").rawText()   // "{bed[0]}"
 * ```
 *
 * @param inner the expression, without its braces
 * @return the expression value
 */
fun expr(inner: String): GValue = GRawExpression("{" + inner + "}")

/**
 * A trailing comment (spec 6): `;<text>`. A leading space is the caller's to include.
 *
 * ```
 * tailComment(" x").rawText()   // "; x"
 * ```
 *
 * @param text the comment's text, after the `;`
 * @return the comment
 */
fun tailComment(text: String): GComment = GTailComment(text)

/**
 * An inline comment (spec 6): `(<text>)`.
 *
 * ```
 * inlineComment("x").rawText()   // "(x)"
 * ```
 *
 * @param text the comment's text, between the parentheses
 * @return the comment
 */
fun inlineComment(text: String): GComment = GInlineComment(text)

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
 *
 * @param lexeme the number's exact characters
 * @return the number token, carrying [lexeme] verbatim
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
 *
 * @param letter the candidate letter
 * @return the letter identifier, in the case given
 */
internal fun identifier(letter: Char): GLetter {
    require((letter >= 'A' && letter <= 'Z') || (letter >= 'a' && letter <= 'z')) {
        "spec 4.3: an identifier is a letter A-Z, got '$letter'"
    }
    return GLetter(letter)
}
