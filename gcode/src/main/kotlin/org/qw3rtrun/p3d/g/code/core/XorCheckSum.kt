package org.qw3rtrun.p3d.g.code.core

import org.qw3rtrun.p3d.g.code.core.token.GInt

/**
 * A streaming checksum over the bytes of one line (GCODE_spec.md section 8): characters in, one at a
 * time, integer state, the value out at the end. No buffering and no second pass, so the same code
 * runs over a growing serial buffer and over a whole file.
 *
 * One instance carries the state of one line, so a fresh one is needed per line.
 * `checkSumCalculatorFor` picks the implementation a received field's width calls for; a sender picks
 * one when it frames with `GEncoder.frame`.
 *
 * ```
 * val sum: CheckSumCalculator = XorCheckSum()
 * sum.add("N1 G28")
 * sum.get()   // GInt(18)
 * ```
 */
interface CheckSumCalculator {

    /**
     * Adds one character to the running checksum. It takes a `Char`, not a byte, and outside 7-bit
     * ASCII a character is not the byte a transport sends: [XorCheckSum] folds in the whole UTF-16
     * code unit and [Crc16CheckSum] its low eight bits, so for `é` neither agrees with a checksum
     * computed over its UTF-8 bytes `C3 A9`.
     *
     * ```
     * val sum = XorCheckSum()
     * sum.add('N')
     * sum.add('1')
     * sum.get()   // GInt(78 xor 49)
     * ```
     *
     * @param ch the next character of the covered range
     */
    fun add(ch: Char)

    /**
     * The checksum of every character added so far, with the lexeme the algorithm writes it as.
     *
     * ```
     * val sum = XorCheckSum()
     * sum.add("N1 G28")
     * sum.get()   // GInt(18)
     * ```
     *
     * @return the current value as a number token
     */
    fun get(): GInt

    /**
     * Every character of [str], in order - spec 8.3 covers the bytes as transmitted, so this is how
     * a caller that has built the line as text feeds it in.
     *
     * An index loop rather than `forEach`: this runs for every byte of every line, and a stdlib
     * inline extension is one more thing a port has to recognise for no gain.
     *
     * ```
     * val sum = XorCheckSum()
     * sum.add("N1 G28")
     * sum.get()   // GInt(18)
     * ```
     *
     * @param str the covered text
     */
    fun add(str: String) {
        var i = 0
        while (i < str.length) {
            add(str[i])
            i++
        }
    }
}

/**
 * The XOR checksum of GCODE_spec.md section 8.2: every covered character XORed together, masked to
 * one byte, written as 1-3 decimal digits. The default algorithm, and the one Marlin expects.
 *
 * ```
 * val xor = XorCheckSum()
 * xor.add("N1 G28")
 * xor.get()   // GInt(18), so the line is framed as "N1 G28*18"
 * ```
 */
class XorCheckSum : CheckSumCalculator {
    private var sum = 0

    override fun add(ch: Char) {
        sum = sum xor ch.code
    }

    override fun get(): GInt {
        return GInt(sum and 0xff)
    }
}