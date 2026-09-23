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
 */

/**
 * The value behind the first token spelling this letter, or null if there is none.
 *
 * Null also for a letter that is *there* but carries no value - the bare `T` of `G33 T` - because
 * a valued property has no way to say "present, without a value"; [hasWord] is the reading for a
 * parameter modelled as a flag.
 */
internal fun List<GToken>.valueOf(letter: Char): GValue? {
    var i = 0
    while (i < size) {
        val token = this[i]
        if (token is GIdentifier && token.isLetter(letter)) {
            // spec 2.1: a space may separate a field from its value, so `X 10` is one word.
            var j = i + 1
            while (j < size && this[j] is GWhitespace) j++
            val value = if (j < size) this[j] else null
            // The first spelling of the letter wins, value or not: a line carrying `X` and then
            // `X10` is malformed, and quietly preferring the second would hide it.
            return value as? GValue
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
 */
internal fun List<GToken>.hasWord(letter: Char): Boolean =
    any { it is GIdentifier && it.isLetter(letter) }

internal fun List<GToken>.intOf(letter: Char): Int? = when (val v = valueOf(letter)) {
    is GInt -> v.int
    // `X1.0` where an int was documented: take the integral part rather than losing the word.
    is GFloat -> v.value.toInt()
    else -> null
}

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
 */
internal fun List<GToken>.decimalOf(letter: Char): BigDecimal? = when (val v = valueOf(letter)) {
    is GFloat -> v.value
    is GInt -> BigDecimal(v.lexeme)
    else -> null
}

/** Marlin writes booleans as `S1` / `S0` (spec 3.3), so any non-zero number is true. */
internal fun List<GToken>.boolOf(letter: Char): Boolean? = when (val v = valueOf(letter)) {
    is GNumber -> v.number.toDouble() != 0.0
    else -> null
}

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
 */
internal fun List<GToken>.stringArg(vararg letters: Char): String? {
    var i = stringArgStart(letters)

    // From there to the end of the line, in the bytes it was written in: `rawText()` per token is
    // what makes `M117 done: 10.50 (ok)` come back with its digits and its parentheses intact.
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
 */
internal fun List<GToken>.beforeStringArg(vararg letters: Char): List<GToken> =
    subList(0, stringArgStart(letters))

/**
 * The index where the bare string begins: past the run of `<letter><value>` fields spelling one of
 * [letters], and past the whitespace separating it from them (spec 2.1 lets a space sit inside a
 * field, so the pairing is `valueOf`'s).
 *
 * A letter of this command's that carries **no** value ends the run rather than being skipped - the
 * model has no way to say "present, without a value" for a valued parameter, and guessing would
 * swallow a word of the string.
 */
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
        var j = i + 1
        while (j < size && this[j] is GWhitespace) j++
        val value = if (j < size) this[j] else null
        if (value !is GValue) break
        i = j + 1
    }
    return i
}

/** spec 2.2: case-insensitive, through `GIdentifier.isLetter`'s own ASCII folding. */
private fun GIdentifier.isOneOf(letters: CharArray): Boolean {
    var i = 0
    while (i < letters.size) {
        if (isLetter(letters[i])) return true
        i++
    }
    return false
}
