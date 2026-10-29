package org.qw3rtrun.p3d.g.code.core.block

import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.GValue
import org.qw3rtrun.p3d.g.code.core.token.GWhitespace

/**
 * Reading a field off tokens - GCODE_spec.md sections 2.1 and 3.
 *
 * One rule, and the block layer's whole contribution to reading a field: where the value paired
 * with an identifier is. It is stated over token kinds and nothing else, which is why it once sat
 * in the token layer, but the question it answers - which token a field's identifier carries as
 * its value - is a *field* question, and a field is what this layer is for.
 *
 * **What a field *means* is not here.** Which letter heads a command, what counts as a command
 * number, where a command word ends - spec 4 - are the command layer's, in `core.command`'s
 * `GHead.kt`, because each of them answers in words rather than in token positions. They are
 * built on this rule rather than restating it: spec 2.3 makes `command-word`, `line-number` and
 * `checksum` *specialisations* of `word`, so the head is found with the same pairing every
 * ordinary field is. The layer below never asks it; the layers above reuse this copy.
 */

/**
 * The index of the value paired with the identifier at [idIndex], or -1 when it carries none.
 *
 * spec 2.1: whitespace may separate an identifier from its value, so `N 1`, `* 12` and `X  10`
 * each assemble across it. **Only whitespace is crossed** - a comment between the two ends the
 * field, and so does another identifier, which is what makes `N*` a bare `N` rather than an `N`
 * carrying a `*`. A line break is a separator but not whitespace, so it ends the field too.
 *
 * It lives beside the head reader because it is the field layer's rule, even though it is stated
 * over token kinds (`GWhitespace`, `GValue`) and nothing else. spec 2.3 makes `command-word`,
 * `line-number` and `checksum` specialisations of `word`, so the liner, the command head and the
 * decoders all reuse it - one way, from above, which is the direction the layering allows.
 *
 * This is the module's only copy of that rule. It was five: the reader pairing `N` with its number
 * and `*` with its checksum, the command reader pairing any letter with its value and `G` with
 * `1`, and the Marlin decoders pairing a documented letter with what follows it. The rule decides
 * whether `N 1` is a line number and whether `X 10` is one field or two, so a spec correction had
 * to reach all five and would have reached three.
 *
 * [idIndex] is not checked: a caller that has found an identifier passes its index, and one that
 * passes anything else gets the token behind it, which is the same question asked of a different
 * position rather than an error.
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
