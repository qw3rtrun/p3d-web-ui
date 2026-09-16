package org.qw3rtrun.p3d.g.marlin

import org.qw3rtrun.p3d.g.code.core.token.GFloat
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GNumber
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GQuotedString
import org.qw3rtrun.p3d.g.code.core.token.GValue
import org.qw3rtrun.p3d.g.code.core.token.GWord
import java.math.BigDecimal

/**
 * Reading one parameter back out of a command's word list, one accessor per shape a Marlin
 * parameter can take.
 *
 * Hand-written, and the generated command classes in `MarlinCommands.kt` are built on it: a fix
 * here reaches all 295 of them, which is the point of keeping it out of the generated file.
 *
 * Every accessor returns `null` for "the command does not carry this letter", because on this side
 * of the DSL an absent optional parameter is the normal case - 831 of Marlin's 882 documented
 * parameters are optional.
 */

/** The value of the first word with this letter, or null if the command has no such word. */
internal fun List<GWord>.valueOf(letter: Char): GValue? =
    (firstOrNull { it.isLetter(letter) } as? GParameterWord<*>)?.value

/**
 * Whether the command carries this letter at all - the reading for a flag (spec 3.2).
 *
 * True for a bare `X` and also for an `X5`: a flag parameter in the model cannot hold the 5, so a
 * value the model does not expect is reported as presence rather than silently dropped.
 */
internal fun List<GWord>.hasWord(letter: Char): Boolean = any { it.isLetter(letter) }

internal fun List<GWord>.intOf(letter: Char): Int? = when (val v = valueOf(letter)) {
    is GInt -> v.int
    // `X1.0` where an int was documented: take the integral part rather than losing the word.
    is GFloat -> v.value.toInt()
    else -> null
}

internal fun List<GWord>.longOf(letter: Char): Long? = when (val v = valueOf(letter)) {
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
internal fun List<GWord>.decimalOf(letter: Char): BigDecimal? = when (val v = valueOf(letter)) {
    is GFloat -> v.value
    is GInt -> BigDecimal(v.lexeme)
    else -> null
}

/** Marlin writes booleans as `S1` / `S0` (spec 3.3), so any non-zero number is true. */
internal fun List<GWord>.boolOf(letter: Char): Boolean? = when (val v = valueOf(letter)) {
    is GNumber -> v.number.toDouble() != 0.0
    else -> null
}

internal fun List<GWord>.stringOf(letter: Char): String? = when (val v = valueOf(letter)) {
    is GQuotedString -> v.string
    else -> null
}
