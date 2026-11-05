package org.qw3rtrun.p3d.g.code.core.block

import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.GValue
import org.qw3rtrun.p3d.g.code.core.token.GWhitespace

/**
 * Reading a field off tokens - GCODE_spec.md sections 2.1 and 3.
 *
 * One rule, and the block layer's whole contribution to reading a field: where the value paired with
 * an identifier is. Stated over token kinds alone, which is why it once sat in the token layer - but
 * which token a field's identifier carries as its value is a field question, not a token one.
 *
 * What a field means is not here: which letter heads a command, what counts as a command number,
 * where a command word ends (spec 4) are the command layer's, in `core.command`'s `GHead.kt`. They
 * are built on this rule rather than restating it - spec 2.3 makes `command-word`, `line-number` and
 * `checksum` specialisations of `word`, so the head is found with the same pairing every field is.
 */

/**
 * The index of the value paired with the identifier at [idIndex], or -1 when it carries none.
 *
 * Spec 2.1: whitespace may separate an identifier from its value (`N 1`, `* 12`, `X  10`), but only
 * whitespace is crossed - a comment or another identifier ends the field, which is what makes `N*` a
 * bare `N`. A line break is a separator but not whitespace, so it ends the field too.
 *
 * This is the module's only copy of the rule. It was five - the block reader pairing `N`/`*` with
 * their values, the command reader pairing any letter with its value and `G` with its number, and
 * the Marlin decoders pairing a documented letter with what follows it - so one spec correction
 * reached all five instead of three.
 *
 * [idIndex] is not checked: a caller passes the index of an identifier it has found; anything else
 * just asks the same question of a different position.
 *
 * ```
 * GTokenizer.parse("N 1").toList().valueIndex(0)        // 2
 * GTokenizer.parse("N*").toList().valueIndex(0)         // -1
 * GTokenizer.parse("X (c) 10").toList().valueIndex(0)   // -1 - a comment ends the field
 * ```
 *
 * @receiver the tokens to read
 * @param idIndex the index of the identifier whose value is wanted
 * @return the index of its value token, or -1 when it has none
 */
fun List<GToken>.valueIndex(idIndex: Int): Int {
    var i = idIndex + 1
    while (i < size && this[i] is GWhitespace) i++
    if (i < size && this[i] is GValue) return i
    return -1
}
