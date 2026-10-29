package org.qw3rtrun.p3d.g.code.core.block

/**
 * How long a line may be: GCODE_spec.md section 1.3, as arithmetic instead of a comment.
 *
 * **The firmware's receive buffer is the limit, and overrunning it is not a soft failure.** Marlin
 * reads a line into `MAX_CMD_SIZE` bytes and truncates what does not fit, so an over-long numbered
 * block arrives with its tail missing - checksum included - and is rejected. Spec 9 classes it as a
 * **structural error**, beside `N` not first and `*` not last.
 *
 * **And it is the one error a resend cannot repair.** Spec 8.5's recovery replays the block *byte
 * for byte*, which is the whole point of a send window holding the text rather than the command - so
 * the replay is truncated at exactly the same byte, rejected again, and asked for again. A host that
 * sends one line too long does not lose a line; it stops making progress. That is why this is
 * measured before sending rather than discovered afterwards.
 *
 * **Nothing here names the layers that use it**, and the layering test enforces that: a `block` file
 * may name `token` and `block`, so the send window and the encoder are described in prose rather than
 * linked. The dependency runs the other way, which is what lets this travel with the core.
 *
 * ```
 * GLineLimit.fits("N1 G28*18")              // true
 * GLineLimit.overrun("N1 " + "X".repeat(120), limit = GLineLimit.MARLIN)   // 27
 * GLineLimit.payloadBudget(number = 1)      // 89 - what may follow `N1 ` and still fit with a `*nnn`
 * ```
 *
 * @see <a href="https://github.com/qw3rtrun/p3d-web-ui/blob/master/doc/specs/GCODE_spec.md#13-line-length">spec 1.3</a>
 */
object GLineLimit {

    /**
     * Marlin's `MAX_CMD_SIZE`, 96 bytes by default - **including** the `N` prefix and the `*`
     * checksum field, **excluding** the terminator. The default for everything here, because it is
     * the firmware this project talks to and the smallest of the three.
     */
    const val MARLIN = 96

    /** RepRapFirmware's limit, 256 characters. */
    const val REPRAP_FIRMWARE = 256

    /** RS274/NGC's limit: "The maximum line length is 256 characters". */
    const val RS274 = 256

    /**
     * Spec 1.3's guidance for a generator that must interoperate with **unknown** firmware: keep the
     * payload at or under 76 characters, so that an `N` prefix and a `*` suffix still fit inside a
     * 96-byte buffer whatever line number the block ends up carrying.
     *
     * Deliberately more conservative than [payloadBudget], which answers the same question exactly
     * for a line number that is already known. Use this one when the number is not: a block built
     * now may be numbered much later, and by a sender that has reordered the queue in between.
     */
    const val INTEROPERABLE_PAYLOAD = 76

    /** The widest `*` field [XorCheckSum] can produce: spec 8.2 masks to a byte, so `0`..`255`. */
    const val XOR_DIGITS = 3

    /** The width of a [Crc16CheckSum] field: spec 8.4 is five digits, always. */
    const val CRC16_DIGITS = 5

    /**
     * How many bytes [line] occupies on the wire, **not counting one trailing terminator**.
     *
     * Spec 1.3 measures the buffer that way - 96 bytes "excluding the terminator" - and spec 1.2
     * makes the terminator either `LF` or `CRLF`, so both characters of a `CRLF` come off. Only one
     * terminator is removed: a line carrying two is two lines, and measuring it as one would be
     * answering a question nobody asked.
     *
     * **Bytes, not characters, and that distinction is the point of this function.** The wire is
     * 7-bit ASCII (spec 1.1), so for a conforming line the two are equal and this could have been
     * `length`. The case that makes it worth writing out is the one a host actually hits: a
     * rest-of-line string (spec 3.4a) carrying text a *user* typed - `M117 Café` - where a single
     * character is two bytes of `MAX_CMD_SIZE`. Counting characters there under-counts, passes a
     * line the firmware will truncate, and lands in the resend loop this object exists to prevent.
     * Such a line is already outside spec 1.1, which is a reason to measure it honestly rather than
     * a reason to assume it away.
     *
     * UTF-8 widths, by code point: one byte below `U+0080`, two below `U+0800`, four for a surrogate
     * pair, three otherwise. A high surrogate is counted as the whole four and its low partner as
     * nothing, so a pair is counted once; an unpaired surrogate counts three, which is what a UTF-8
     * encoder writes for the replacement character it would substitute.
     *
     * ```
     * GLineLimit.wireLength("G28")        // 3
     * GLineLimit.wireLength("G28\r\n")    // 3 - the terminator is not in the buffer
     * GLineLimit.wireLength("M117 Café")  // 10, not 9
     * ```
     *
     * @param line one line, with or without its terminator
     * @return the number of bytes the firmware's buffer would hold
     */
    fun wireLength(line: String): Int {
        var end = line.length
        if (end > 0 && line[end - 1] == '\n') {
            end--
            if (end > 0 && line[end - 1] == '\r') end--
        }

        var bytes = 0
        var i = 0
        while (i < end) {
            val c = line[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                isHighSurrogate(c) && i + 1 < end && isLowSurrogate(line[i + 1]) -> 4
                isLowSurrogate(c) -> 0
                else -> 3
            }
            i++
        }
        return bytes
    }

    /**
     * Whether [line] fits a buffer of [limit] bytes.
     *
     * ```
     * GLineLimit.fits("N1 G28*18")                      // true
     * GLineLimit.fits("X".repeat(300), RS274)           // false
     * ```
     *
     * @param line one line, with or without its terminator
     * @param limit the buffer size in bytes; [MARLIN] by default
     * @return true when the whole line reaches the firmware
     */
    fun fits(line: String, limit: Int = MARLIN): Boolean = wireLength(line) <= limit

    /**
     * How many bytes [line] is over [limit], or **0** when it fits.
     *
     * A number rather than a boolean because it is what a message can say: "11 bytes too long" tells
     * whoever built the line how much has to come out, and `false` does not.
     *
     * ```
     * GLineLimit.overrun("G28")                    // 0
     * GLineLimit.overrun("X".repeat(100))          // 4
     * ```
     *
     * @param line one line, with or without its terminator
     * @param limit the buffer size in bytes; [MARLIN] by default
     * @return the excess in bytes, zero when there is none
     */
    fun overrun(line: String, limit: Int = MARLIN): Int {
        val over = wireLength(line) - limit
        return if (over > 0) over else 0
    }

    /**
     * How many payload bytes a block numbered [number] may carry and still fit [limit] once framed.
     *
     * The framing spec 7 and 8 add is `N<number><space>` in front and `*<digits>` behind, which is
     * exactly what the encoder's framer writes, with no space before the `*`.
     * So the budget is the limit less those two, and it **shrinks as the line number grows** -
     * which is the part that cannot be precomputed and the reason this takes a number at all. At
     * `N1` under Marlin a payload may be 89 bytes; at `N100000` it may be 84.
     *
     * [checksumDigits] is the **widest** field the algorithm can produce, not the width of any
     * particular value: a budget that assumed `*7` would be wrong for the next block whose checksum
     * happens to be `*213`. [XOR_DIGITS] for spec 8.2, [CRC16_DIGITS] for spec 8.4.
     *
     * Returns 0 rather than a negative when the framing alone exceeds the limit, so a caller may
     * compare a length against it without a second guard.
     *
     * ```
     * GLineLimit.payloadBudget(1)                              // 89
     * GLineLimit.payloadBudget(100000)                         // 84
     * GLineLimit.payloadBudget(1, checksumDigits = CRC16_DIGITS)   // 87
     * ```
     *
     * @param number the line number the block will carry in its `N` field
     * @param limit the buffer size in bytes; [MARLIN] by default
     * @param checksumDigits the widest `*` field the algorithm writes; [XOR_DIGITS] by default
     * @return how many bytes of payload still fit, or 0 when the framing alone does not
     */
    fun payloadBudget(number: Int, limit: Int = MARLIN, checksumDigits: Int = XOR_DIGITS): Int {
        // `N` + the digits + the space, then `*` + the digits. Three literal characters in all.
        val framing = 3 + digitsOf(number) + checksumDigits
        val budget = limit - framing
        return if (budget > 0) budget else 0
    }

    /**
     * How many characters [number] is written with, a minus sign included.
     *
     * Spec 7.1 has no negative line numbers, and nothing in this module produces one - but
     * `GEncoder.frame` takes an `Int` and would write `N-1 `, so a budget that ignored the sign
     * would be one byte optimistic for a number this module should never have been handed.
     */
    private fun digitsOf(number: Int): Int {
        if (number == 0) return 1
        var rest = number
        var digits = if (rest < 0) 1 else 0
        while (rest != 0) {
            digits++
            rest /= 10
        }
        return digits
    }

    private fun isHighSurrogate(c: Char) = c.code >= 0xD800 && c.code <= 0xDBFF

    private fun isLowSurrogate(c: Char) = c.code >= 0xDC00 && c.code <= 0xDFFF
}
