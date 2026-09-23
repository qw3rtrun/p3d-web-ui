package org.qw3rtrun.p3d.g.code.core.token

/**
 * Reading a field off tokens - GCODE_spec.md sections 2.1, 3 and 4 - and the one field that is a
 * command: its head.
 *
 * **Functions over tokens, not a parser object.** Nothing here has state, nothing here builds a
 * `GWord`, and that is the point: a decoder asks where a value is and what command a line opens
 * with, and both are answerable from token positions alone. The class that used to own them,
 * `GCommandParser`, also assembled every field of a line into words and grouped them into commands
 * - a second, weaker reader of the same bytes, weaker because words are built without the command
 * number and so cannot know that `M117 Hello World` has one argument rather than ten fields. That
 * half had no production caller left once `MarlinCommands.decode` existed, and now lives in the
 * test apparatus that measures the module against a real corpus.
 *
 * spec 2.3 is the reason the two sit together: `command-word`, `line-number` and `checksum` are
 * *specialisations* of `word` - lexically ordinary fields whose letters are given structural
 * meaning - so the rule that finds a value is the rule the head is found with.
 */

/**
 * The index of the value paired with the identifier at [idIndex], or -1 when it carries none.
 *
 * spec 2.1: whitespace may separate an identifier from its value, so `N 1`, `* 12` and `X  10`
 * each assemble across it. **Only whitespace is crossed** - a comment between the two ends the
 * field, and so does another identifier, which is what makes `N*` a bare `N` rather than an `N`
 * carrying a `*`.
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
 */
fun valueIndex(tokens: List<GToken>, idIndex: Int): Int {
    var i = idIndex + 1
    while (i < tokens.size && tokens[i] is GWhitespace) i++
    if (i < tokens.size && tokens[i] is GValue) return i
    return -1
}

/**
 * The command word [tokens] start with, or null when they do not start one.
 *
 * **Tokens in, one word out** - the narrowest thing a decoder needs, and the reason it is here
 * rather than a copy inside the protocol layer: what a command word is, is this file's rule, and a
 * decoder disagreeing with it would accept heads no line can carry. Only the head is read; what a
 * command's *parameters* mean is the decoder's own business, because it is the first thing in the
 * stack that knows which command it holds.
 *
 * The word is **canonically spelled**: it carries `[id, number]` and nothing else, so `G1` and the
 * `G 1` spec 2.1 also allows produce the same word and a decoder can compare it against its own
 * head without knowing what whitespace the line happened to contain. [headEnd] says where the
 * tokens it was read from end.
 *
 * `T` may head the command, because [tokens] are one command's - its first position is by
 * definition the position spec 4.2's parameter reading cannot apply to.
 */
fun headWord(tokens: List<GToken>): GParameterWord<GNumber>? {
    val id = headIdIndex(tokens)
    if (id < 0) return null
    val num = headNumIndex(tokens, id)
    if (num < 0) return null
    return GParameterWord(tokens[id] as GIdentifier, tokens[num] as GNumber)
}

/**
 * [word] as the key two heads are **matched** by: its letter folded to upper case.
 *
 * spec 2.2: NIST and RepRapFirmware are case-insensitive outside comments and strings, and Marlin
 * is too when `GCODE_CASE_INSENSITIVE` is compiled in - so `m104` is a line a host will be handed,
 * and a decoder has to answer to it. **Reading is where the tolerance belongs**; spec 2.2's rule
 * for generators is the opposite one, always emit uppercase, and the DSL does. [headWord]
 * deliberately keeps the letter as the line spelled it, because that is provenance; this is the
 * form to compare, and the two are separate for exactly that reason.
 *
 * The **number is untouched**: its lexeme is part of its identity (spec 4.1, [GNumber]), so `M0105`
 * is still not `M105`. Case is a property of the writing, digits are not.
 */
fun headKey(word: GParameterWord<*>): GParameterWord<*> {
    val id = word.id
    if (id !is GLetter) return word
    val letter = id.letter
    if (letter < 'a' || letter > 'z') return word
    // Explicit ASCII, not `uppercaseChar()`: spec 1.1's wire format is 7-bit, and a
    // locale-dependent or Unicode-wide fold is the bug `GIdentifier.isLetter` documents.
    return GParameterWord(GLetter((letter.code - 32).toChar()), word.value)
}

/**
 * Where the command word that starts [tokens] ends - the index of the first parameter token - or 0
 * when [tokens] do not start a command and nothing has been consumed.
 */
fun headEnd(tokens: List<GToken>): Int {
    val id = headIdIndex(tokens)
    if (id < 0) return 0
    val num = headNumIndex(tokens, id)
    return if (num < 0) 0 else num + 1
}

/**
 * spec 4.1: `G`, `M` and `T` head commands. `T` only while [first], because spec 4.2 also lists it
 * as a conventional parameter letter and 40 corpus lines use it that way - `G29 T`, `G12 P1 S1 T3`,
 * `G26 C P T3.0`. Splitting at every `T` misparses all of them.
 *
 * spec 2.2: the dialects are case-insensitive, compared as explicit ASCII rather than folded -
 * `uppercase()` is locale-dependent, and a Turkish locale changes `i`/`I`.
 *
 * `D` (spec 4.1, Marlin debug builds) is deliberately absent: spec 4.2 also lists `D` as a
 * parameter letter (diameter, PID `D`), so treating it as a command would misread those.
 */
fun isCommandLetter(c: Char, first: Boolean): Boolean =
    c == 'G' || c == 'g' ||
        c == 'M' || c == 'm' ||
        (first && (c == 'T' || c == 't'))

/**
 * spec 4.1: `<unsigned-int>` optionally followed by a subcode, `.` plus `<unsigned-int>`.
 *
 * Reads the lexeme, not the value: leading zeros are insignificant (`G1` = `G01` = `G001`) so the
 * value cannot tell them apart, while a sign or a bare trailing dot has to be rejected and only the
 * lexeme still shows it.
 *
 * Shared with the command builders in `code/dsl/G.kt` so that the DSL cannot construct a command
 * this file would refuse to read back. That symmetry is the point: a builder and a reader
 * disagreeing about what a command number is would let a round-trip test pass on input no firmware
 * accepts.
 */
fun isCommandNumber(lexeme: String): Boolean {
    var i = 0
    while (i < lexeme.length && digit(lexeme[i])) i++
    if (i == 0) return false                    // a sign, a leading dot, or no digits at all
    if (i == lexeme.length) return true         // plain command number
    if (lexeme[i] != '.') return false
    i++
    val subcodeStart = i
    while (i < lexeme.length && digit(lexeme[i])) i++
    return i > subcodeStart && i == lexeme.length
}

/** The index of the command letter [tokens] open with, or -1. */
private fun headIdIndex(tokens: List<GToken>): Int {
    var i = 0
    // Leading whitespace only: anything else in front of the letter means these are not one
    // command's tokens, and guessing which of them to skip is how a head goes missing.
    while (i < tokens.size && tokens[i] is GWhitespace) i++
    if (i >= tokens.size) return -1
    val id = tokens[i]
    // spec 4.3: only A-Z are identifiers, so `*` - a GChecksum - heads nothing.
    if (id !is GLetter) return -1
    if (!isCommandLetter(id.letter, true)) return -1
    return i
}

/** The index of the command number behind the letter at [idIndex], or -1. */
private fun headNumIndex(tokens: List<GToken>, idIndex: Int): Int {
    // spec 2.1: a space may separate a field from its value, so `G 1` is one word.
    val j = valueIndex(tokens, idIndex)
    if (j < 0) return -1
    // spec 4.1: a command word is the letter *followed by a number*. A bare `G` is a flag.
    val value = tokens[j]
    if (value !is GNumber) return -1
    if (!isCommandNumber(value.lexeme)) return -1
    return j
}

private fun digit(c: Char) = c >= '0' && c <= '9'
