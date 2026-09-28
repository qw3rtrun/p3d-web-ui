package org.qw3rtrun.p3d.g.code.core.token

/**
 * The index of the value paired with the identifier at [idIndex], or -1 when it carries none.
 *
 * spec 2.1: whitespace may separate an identifier from its value, so `N 1`, `* 12` and `X  10`
 * each assemble across it. **Only whitespace is crossed** - a comment between the two ends the
 * field, and so does another identifier, which is what makes `N*` a bare `N` rather than an `N`
 * carrying a `*`. A line break is a separator but not whitespace, so it ends the field too.
 *
 * It lives in the token layer because the rule is purely lexical: it is stated over token kinds
 * (`GWhitespace`, `GValue`) and nothing else. spec 2.3 makes `command-word`, `line-number` and
 * `checksum` specialisations of `word`, so the liner, the command head and the decoders all reuse
 * it - one way, from above, which is the direction the layering allows.
 *
 * This is the module's only copy of that rule. It was five: the liner pairing `N` with its number
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
 * valueIndex(GTokenizer.parse("N 1").toList(), 0)        // 2
 * valueIndex(GTokenizer.parse("N*").toList(), 0)         // -1
 * valueIndex(GTokenizer.parse("X (c) 10").toList(), 0)   // -1 - a comment ends the field
 * ```
 *
 * @param tokens the tokens to read
 * @param idIndex the index of the identifier whose value is wanted
 * @return the index of its value token, or -1 when it has none
 */
fun valueIndex(tokens: List<GToken>, idIndex: Int): Int {
    var i = idIndex + 1
    while (i < tokens.size && tokens[i] is GWhitespace) i++
    if (i < tokens.size && tokens[i] is GValue) return i
    return -1
}
