package org.qw3rtrun.p3d.g.protocol

import org.qw3rtrun.p3d.g.code.core.token.asciiFold

/**
 * Reading a reply line by hand: the primitives the decoders scan with, instead of a regex.
 *
 * **Why not `java.util.regex`.** A reply decoder is the one place a host *reads* what a printer
 * says, and the pattern is the whole specification of what it accepts - so it has to be reviewable
 * against the wire format, which a condensed pattern is not. Six of the patterns these replaced
 * also used `(?>...)` atomic groups, which exist in Java and PCRE and in neither Rust's `regex`
 * crate nor C: a port could not have translated them, only re-derived them, with less in front of
 * it than this file has.
 *
 * Case folding is **explicit ASCII**, through the same `asciiFold` the token layer compares
 * identifiers with: Marlin's replies are 7-bit (spec 1.1), and `ignoreCase = true` folds the whole
 * Unicode table in a locale-dependent way - a Turkish locale changes `i`/`I`, and U+212A KELVIN
 * SIGN lowercases to `k`. One copy of that rule, in `core.token`.
 *
 * Every function takes an index and returns one, so a scan is a walk with no allocation until the
 * one `substring` that yields a field's text.
 *
 * ```
 * "Resend: 12".startsWithAscii("resend")   // true
 * "ok  12".blanksEnd(2)                    // 4
 * "12ab".digitsEnd(0)                      // 2
 * ```
 */

/**
 * Whether [this] has [prefix] at [at], comparing ASCII letters case-insensitively.
 *
 * ```
 * "SD printing byte 1/2".startsWithAscii("sd printing byte")   // true
 * ```
 *
 * @receiver the line being scanned
 * @param prefix the text to look for
 * @param at where in the line to look; defaults to the start
 * @return true when the line carries that prefix there
 */
internal fun String.startsWithAscii(prefix: String, at: Int = 0): Boolean {
    if (at < 0 || at + prefix.length > length) return false
    var i = 0
    while (i < prefix.length) {
        if (asciiFold(this[at + i]) != asciiFold(prefix[i])) return false
        i++
    }
    return true
}

/**
 * The index of the first character at or after [from] that is not a space or a tab.
 *
 * Spec 2.1's two separators and nothing else - not `Char.isWhitespace()`, which is the whole
 * Unicode space category.
 *
 * ```
 * "a  b".blanksEnd(1)   // 3
 * ```
 *
 * @receiver the line being scanned
 * @param from where to start skipping
 * @return the index past the run of blanks, which is [from] when there is none
 */
internal fun String.blanksEnd(from: Int): Int {
    var i = from
    while (i < length && (this[i] == ' ' || this[i] == '\t')) i++
    return i
}

/**
 * Where the number at [from] ends, or -1 when there is not one there.
 *
 * spec 3.1's shape, as the patterns these decoders replaced all spelled it
 * (`[-+]?(?>[0-9]*\.)?[0-9]+`): an optional sign, optional digits, an optional point, and **at
 * least one digit somewhere**. So `5`, `-5`, `+1.5` and `.5` are numbers; `12.` is not, because a
 * point with nothing behind it is a number the firmware did not finish writing.
 *
 * Returns an index rather than a value, like everything else here: the one `substring` happens
 * where the field is actually taken, and a caller that only wants to know where the number ended
 * pays nothing.
 *
 * ```
 * "X:-1.5".numberEnd(2)   // 6
 * "X:12.".numberEnd(2)    // -1
 * ```
 *
 * @receiver the line being scanned
 * @param from where the number should start
 * @return the index past the number, or -1 when [from] does not start one
 */
internal fun String.numberEnd(from: Int): Int {
    var i = from
    if (i < length && (this[i] == '-' || this[i] == '+')) i++

    val whole = digitsEnd(i)
    if (whole < length && this[whole] == '.') {
        val fraction = digitsEnd(whole + 1)
        return if (fraction == whole + 1) -1 else fraction
    }
    return if (whole == i) -1 else whole
}

/**
 * Whether [this] and [other] are the same text, comparing ASCII letters case-insensitively.
 *
 * The two-sided form of [startsWithAscii], for comparing fields a scan has already cut out -
 * `TRIGGERED` against what the line said, `true` against a capability's value. It does **not**
 * trim; [equalsAscii] is the whole-line form that does.
 *
 * ```
 * "TRIGGERED".sameAscii("triggered")   // true
 * ```
 *
 * @receiver one of the two texts
 * @param other the other
 * @return true when they differ only in the case of ASCII letters
 */
internal fun String.sameAscii(other: String): Boolean {
    if (length != other.length) return false
    var i = 0
    while (i < length) {
        if (asciiFold(this[i]) != asciiFold(other[i])) return false
        i++
    }
    return true
}

/**
 * The index of the first character at or after [from] that is neither a blank nor a terminator.
 *
 * Spec 2.1's space and tab, and spec 1.2's `CR` and `LF`. Those four and nothing else: `trim()`
 * strips the whole Unicode space category, which on a 7-bit wire (spec 1.1) is a promise about
 * bytes a printer cannot send and a difference a port would have to reproduce.
 *
 * ```
 * " \tok\r\n".edgeEnd(0)   // 2
 * ```
 *
 * @receiver the line being scanned
 * @param from where to start skipping
 * @return the index past the run, which is [from] when there is none
 */
internal fun String.edgeEnd(from: Int): Int {
    var i = from
    while (i < length && isEdge(this[i])) i++
    return i
}

/**
 * The index just past the last character that is neither a blank nor a terminator - [edgeEnd]
 * from the other end.
 *
 * @receiver the line being scanned
 * @return the exclusive end of the line's content, which is 0 for a line of nothing else
 */
internal fun String.edgeStart(): Int {
    var i = length
    while (i > 0 && isEdge(this[i - 1])) i--
    return i
}

private fun isEdge(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'

/**
 * [this] without the blanks and terminators at either end - spec 2.1 and 1.2, and no other
 * character.
 *
 * **It still allocates**, like the `trim()` it replaces, and that is the half of this not yet
 * fixed: a decoder that trimmed and then scanned indices could do both in one walk, and eight
 * decoders in a chain each trim the same line. The correctness half - *which* characters count -
 * is what this settles. Recorded rather than left to be rediscovered.
 *
 * ```
 * " ok\r\n".asciiTrim()   // "ok"
 * ```
 *
 * @receiver the line being scanned
 * @return the line's content, or an empty string when it has none
 */
internal fun String.asciiTrim(): String {
    val from = edgeEnd(0)
    val to = edgeStart()
    return if (from >= to) "" else substring(from, to)
}

/**
 * Whether [this], trimmed per [asciiTrim], is exactly [word] - the shape of every reply that *is*
 * a keyword: `ok`, `wait`, `start`, `Not SD printing`, `Reporting endstop status`.
 *
 * Walks the line in place rather than trimming it, so deciding that a line is not the keyword
 * costs no allocation at all - which matters because every one of those decoders is asked about
 * every line that arrives.
 *
 * ```
 * " OK\r\n".equalsAscii("ok")   // true
 * "ok P15 B3".equalsAscii("ok") // false
 * ```
 *
 * @receiver the line being scanned
 * @param word the keyword to compare against
 * @return true when the line carries that word and nothing else
 */
internal fun String.equalsAscii(word: String): Boolean {
    val from = edgeEnd(0)
    val to = edgeStart()
    if (to - from != word.length) return false
    var i = 0
    while (i < word.length) {
        if (asciiFold(this[from + i]) != asciiFold(word[i])) return false
        i++
    }
    return true
}

/**
 * The index of the first character at or after [from] that is not an ASCII digit.
 *
 * `0`-`9` by explicit comparison: `Char.isDigit()` accepts every Unicode decimal digit, which is
 * how `X١` once lexed as a number.
 *
 * ```
 * "12ab".digitsEnd(0)   // 2
 * "ab".digitsEnd(0)     // 0 - no digits, so the run is empty
 * ```
 *
 * @receiver the line being scanned
 * @param from where to start
 * @return the index past the run of digits, which is [from] when there is none
 */
internal fun String.digitsEnd(from: Int): Int {
    var i = from
    while (i < length && this[i] >= '0' && this[i] <= '9') i++
    return i
}
