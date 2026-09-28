package org.qw3rtrun.p3d.g.marlin

import org.qw3rtrun.p3d.g.code.core.token.GFloat
import org.qw3rtrun.p3d.g.code.core.token.GIdentifier
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GLineBreak
import org.qw3rtrun.p3d.g.code.core.token.GNumber
import org.qw3rtrun.p3d.g.code.core.token.GQuotedString
import org.qw3rtrun.p3d.g.code.core.token.GTailComment
import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.GValue
import org.qw3rtrun.p3d.g.code.core.token.GWhitespace
import org.qw3rtrun.p3d.g.code.core.block.valueIndex
import java.math.BigDecimal

/**
 * Reading one parameter back out of a command's tokens, one accessor per shape a Marlin
 * parameter can take.
 *
 * Hand-written, and the 295 generated command classes under `command/` are built on it: a fix here
 * reaches all of them, which is the point of keeping it out of the generated files.
 *
 * **Tokens, not words.** A decoder is handed the tokens that followed its command's head, because
 * pairing an identifier with a value is only obviously right for the ordinary commands - see
 * `GRqDecoder` - and so it is done here, where the command is known, rather than under it. The
 * pairing itself is spec 2.1 and 3: an identifier, optional whitespace, the value.
 *
 * Every accessor returns `null` for "the command does not carry this letter", because on this side
 * of the DSL an absent optional parameter is the normal case - 831 of Marlin's 882 documented
 * parameters are optional.
 *
 * ```
 * val params = GTokenizer.parse(" X10 T").toList()
 * params.valueOf('X')   // GInt(10)
 * params.hasWord('T')   // true
 * ```
 */

/**
 * The value behind the first token spelling this letter, or null if there is none.
 *
 * Null also for a letter that is *there* but carries no value - the bare `T` of `G33 T` - because
 * a valued property has no way to say "present, without a value"; [hasWord] is the reading for a
 * parameter modelled as a flag.
 *
 * The pairing is spec 2.1's, which is `valueIndex`'s and not a fourth copy of it here. **The first
 * spelling of the letter wins**, value or not: a line carrying `X` and then `X10` is malformed, and
 * quietly preferring the second would hide it.
 *
 * ```
 * GTokenizer.parse(" X10 T").toList().valueOf('X')   // GInt(10)
 * GTokenizer.parse(" X10 T").toList().valueOf('T')   // null - present, but no value
 * GTokenizer.parse("X X10").toList().valueOf('X')    // null - the first spelling wins
 * ```
 *
 * @receiver the tokens that followed the command's head
 * @param letter the parameter letter, in either case
 * @return the value token paired with the letter's first occurrence, or null
 */
internal fun List<GToken>.valueOf(letter: Char): GValue? {
    var i = 0
    while (i < size) {
        val token = this[i]
        if (token is GIdentifier && token.isLetter(letter)) {
            val j = valueIndex(this, i)
            return if (j < 0) null else this[j] as GValue
        }
        i++
    }
    return null
}

/**
 * Whether the command carries this letter at all - the reading for a flag (spec 3.2).
 *
 * True for a bare `X` and also for an `X5`: a flag parameter in the model cannot hold the 5, so a
 * value the model does not expect is reported as presence rather than silently dropped.
 *
 * ```
 * GTokenizer.parse(" X10 T").toList().hasWord('T')   // true
 * GTokenizer.parse(" X10 T").toList().hasWord('x')   // true - case-insensitive
 * GTokenizer.parse(" X10 T").toList().hasWord('Y')   // false
 * ```
 *
 * @receiver the tokens that followed the command's head
 * @param letter the parameter letter, in either case
 * @return true when any identifier spells the letter
 */
internal fun List<GToken>.hasWord(letter: Char): Boolean =
    any { it is GIdentifier && it.isLetter(letter) }

/**
 * An integer parameter. `X1.0` where an int was documented takes the integral part rather than
 * losing the word.
 *
 * ```
 * GTokenizer.parse("X1.0").toList().intOf('X')   // 1
 * ```
 *
 * @receiver the tokens that followed the command's head
 * @param letter the parameter letter, in either case
 * @return the integer value, or null when the letter carries no number
 */
internal fun List<GToken>.intOf(letter: Char): Int? = when (val v = valueOf(letter)) {
    is GInt -> v.int
    is GFloat -> v.value.toInt()
    else -> null
}

/**
 * A long parameter, for values past `Int`'s range. A decimal takes its integral part, as [intOf]
 * does.
 *
 * ```
 * GTokenizer.parse("S4000000000.0").toList().longOf('S')   // 4000000000L
 * ```
 *
 * @receiver the tokens that followed the command's head
 * @param letter the parameter letter, in either case
 * @return the value as a `Long`, or null when the letter carries no number
 */
internal fun List<GToken>.longOf(letter: Char): Long? = when (val v = valueOf(letter)) {
    is GInt -> v.int.toLong()
    is GFloat -> v.value.toLong()
    else -> null
}

/**
 * A decimal, keeping the digits that were written.
 *
 * `GInt` is accepted as well as `GFloat` because `M140 S60` and `M140 S60.0` are the same
 * temperature, and the encoder writes whichever the caller's scale asked for.
 *
 * ```
 * GTokenizer.parse("S60").toList().decimalOf('S')     // BigDecimal("60")
 * GTokenizer.parse("S60.0").toList().decimalOf('S')   // BigDecimal("60.0")
 * ```
 *
 * @receiver the tokens that followed the command's head
 * @param letter the parameter letter, in either case
 * @return the decimal, with the scale it was written with, or null when the letter carries no number
 */
internal fun List<GToken>.decimalOf(letter: Char): BigDecimal? = when (val v = valueOf(letter)) {
    is GFloat -> v.value
    is GInt -> BigDecimal(v.lexeme)
    else -> null
}

/**
 * A boolean parameter. Marlin writes booleans as `S1` / `S0` (spec 3.3), so any non-zero number is
 * true.
 *
 * ```
 * GTokenizer.parse("S1").toList().boolOf('S')   // true
 * GTokenizer.parse("S0").toList().boolOf('S')   // false
 * ```
 *
 * @receiver the tokens that followed the command's head
 * @param letter the parameter letter, in either case
 * @return whether the number is non-zero, or null when the letter carries no number
 */
internal fun List<GToken>.boolOf(letter: Char): Boolean? = when (val v = valueOf(letter)) {
    is GNumber -> v.number.toDouble() != 0.0
    else -> null
}

/**
 * A quoted-string parameter (spec 3.4), decoded.
 *
 * ```
 * GTokenizer.parse("S\"hi\"").toList().stringOf('S')   // "hi"
 * ```
 *
 * @receiver the tokens that followed the command's head
 * @param letter the parameter letter, in either case
 * @return the string's text, or null when the letter carries no quoted string
 */
internal fun List<GToken>.stringOf(letter: Char): String? = when (val v = valueOf(letter)) {
    is GQuotedString -> v.string
    else -> null
}

/**
 * The command's bare rest-of-line string (spec 3.4a), or null when it carries none. [letters] are
 * **this command's own parameter letters**, and passing them is not optional - see below.
 *
 * The 22 commands Marlin reads a `string_arg` for - `M117`'s message, `M23`/`M28`/`M30`/`M928`'s
 * filename, `M33`'s path, `M810`-`M819`'s macro - take an argument with **no letter in front of
 * it**, and the token layer cannot see it: it has no idea which command it is lexing, so
 * `M117 Hello World` comes back as one `GLetter` per character. Reassembling those characters is
 * this function's job, and it can do it because a decoder knows its own command.
 *
 * **Where the string starts is a per-command question, and there is no universal answer.** It
 * begins at the first token that is not one of *this command's* lettered parameters, so the same
 * bytes read differently under two commands, and correctly so:
 *
 * ```
 * M117 H1 ello World   ->  "H1 ello World"   M117 documents no parameters at all
 * M118 P1 ello World   ->  "ello World"      `P` is M118's, so `P1` is a parameter
 * M118 H1 ello World   ->  "H1 ello World"   `H` is not, so the string starts there
 * M118 Hello World P1  ->  "Hello World P1"  once it has started, everything belongs to it
 * ```
 *
 * That is also Marlin's own reading (`GCodeParser::parse` records a letter as a parameter only
 * when the command claims it, and takes the rest as `string_arg`), and it is why the letters are a
 * parameter here rather than a rule baked in: a scan that paired *any* letter with a value would
 * eat `H1` out of `M117`'s message, and one that paired none would read `M118 P1 x` as the message
 * `P1 x`. Use [beforeStringArg] to read the lettered parameters themselves, so that a `P1` **in**
 * the message is not read as a parameter either.
 *
 * Where it **ends** is spec 3.4a: the rest of the line, except that a `;` still starts a comment,
 * so the string cannot contain one. The tokens run out at the end of the body, which for a framed
 * line is the `*` marker - spec 8.3 already puts the checksum field outside it.
 *
 * A `( )` comment is **not** an end, even though the lexer made a token of it: spec 3.4a names only
 * `;`, and Marlin reads parentheses as comments only in a CNC build (`PAREN_COMMENTS`). So
 * `M117 Hi (there)` is the whole of `Hi (there)`, which is also what keeps a message with brackets
 * in it round-tripping.
 *
 * The whitespace separating the string from what precedes it, and any trailing whitespace before a
 * comment or the terminator, is separation rather than content and is dropped; whitespace *inside*
 * is the whole point and is kept exactly. Nothing left is reported as null, like every other absent
 * parameter here.
 *
 * The string is rebuilt from there to the end of the line in the bytes it was written in:
 * `rawText()` per token is what makes `M117 done: 10.50 (ok)` come back with its digits and its
 * parentheses intact. [letters] match case-insensitively, through `GIdentifier.isLetter`'s own ASCII
 * folding (spec 2.2), so `M118 p1 x` reads `p1` as the parameter.
 *
 * ```
 * GTokenizer.parse(" P1 ello World").toList().stringArg('P')    // "ello World"
 * GTokenizer.parse(" Hi (there) ;c").toList().stringArg()       // "Hi (there)"
 * ```
 *
 * @receiver the tokens that followed the command's head
 * @param letters this command's own lettered parameters
 * @return the bare string, or null when the command carries none
 */
internal fun List<GToken>.stringArg(vararg letters: Char): String? {
    var i = stringArgStart(letters)

    val out = StringBuilder()
    while (i < size) {
        val token = this[i]
        if (token is GTailComment || token is GLineBreak) break
        out.append(token.rawText())
        i++
    }

    var end = out.length
    while (end > 0 && (out[end - 1] == ' ' || out[end - 1] == '\t')) end--
    return if (end == 0) null else out.substring(0, end)
}

/**
 * The tokens in front of a command's bare string - the region its **lettered** parameters may be
 * read from, for the same [letters] [stringArg] is given.
 *
 * A decoder for one of the 22 commands reads its letters from this and its string from the whole,
 * because a letter inside the string is text and not a parameter: `M118 Hello World P1` carries the
 * message `Hello World P1` and no `P`. Scanning the whole line for `P` instead would find that one,
 * which is how a message comes back having quietly set a parameter the sender never wrote.
 *
 * The bare string begins past the run of `<letter><value>` fields spelling one of [letters], and
 * past the whitespace separating it from them (spec 2.1 lets a space sit inside a field, so the
 * pairing is `valueIndex`'s). A letter of this command's that carries **no** value ends the run
 * rather than being skipped - the model has no way to say "present, without a value" for a valued
 * parameter, and guessing would swallow a word of the string. [stringArg] starts at the same index.
 *
 * ```
 * GTokenizer.parse(" P1 Hello World P1").toList().beforeStringArg('P')   // [GSpace, P, 1, GSpace]
 * ```
 *
 * @receiver the tokens that followed the command's head
 * @param letters this command's own lettered parameters
 * @return a view of the tokens in front of the bare string
 */
internal fun List<GToken>.beforeStringArg(vararg letters: Char): List<GToken> =
    subList(0, stringArgStart(letters))

private fun List<GToken>.stringArgStart(letters: CharArray): Int {
    var i = 0
    while (i < size) {
        val token = this[i]
        if (token is GWhitespace) {
            i++
            continue
        }
        if (token !is GIdentifier) break
        if (!token.isOneOf(letters)) break
        val j = valueIndex(this, i)
        if (j < 0) break
        i = j + 1
    }
    return i
}

private fun GIdentifier.isOneOf(letters: CharArray): Boolean {
    var i = 0
    while (i < letters.size) {
        if (isLetter(letters[i])) return true
        i++
    }
    return false
}
