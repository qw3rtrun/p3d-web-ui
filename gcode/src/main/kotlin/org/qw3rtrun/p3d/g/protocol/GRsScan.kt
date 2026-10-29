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
