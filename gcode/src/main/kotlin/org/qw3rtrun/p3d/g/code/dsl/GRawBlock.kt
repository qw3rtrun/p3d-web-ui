package org.qw3rtrun.p3d.g.code.dsl

import org.qw3rtrun.p3d.g.code.core.block.GLineLimit

/**
 * The character-safety gate for a **whole command line somebody typed**, as into a terminal: it
 * returns the line trimmed, ready to hand to `GEncoder.frame`, or throws saying what is wrong with
 * it and where.
 *
 * Every other wire-bound string in this module is built here, out of values this package checked -
 * this one is not. The operator wrote it, nothing parses it, and the transport frames what it is
 * handed byte for byte, so this is the only place between a browser and the serial link where the
 * line is looked at. Three things are refused, in this order:
 *
 * 1. **A blank line**, because there is no block to send - framing one would put `N<n> *<checksum>`
 *    on the wire and spend a line number on nothing.
 * 2. **Four characters**, the same four [bareString] refuses and for the same reasons - `\n` and
 *    `\r` end the line (spec 1.2), so `M105\nM112` is a temperature report *and* an emergency stop
 *    with the `N` word on one side of the break and the checksum on the other; `;` starts a comment
 *    (spec 3.4a, 6) that runs to the end of the line and swallows the checksum a transport appends
 *    behind the payload; `*` is the checksum delimiter itself (spec 8.3), so the firmware reads part
 *    of the text as the checksum; and anything outside printable ASCII `0x20`-`0x7E` is off the
 *    portable wire (spec 1.1). **Spaces are fine** - a typed line is mostly spaces.
 * 3. **More than [GLineLimit.INTEROPERABLE_PAYLOAD] bytes** on the wire, reported as the overrun in
 *    bytes rather than a yes/no, because a number says how much has to come out.
 *
 * That character table is deliberately a **second copy** of [bareString]'s rather than a helper
 * shared with it: the two have the same four rules but different messages - a bare string's refusal
 * ends by pointing at `text("...")`, spec 3.4b's quoted form, which is the way out when a *value*
 * cannot be said bare, and is no way out at all for a typed command line. `bareString`'s exact
 * message text is asserted character by character in `BareStringTest`, so folding the two together
 * would mean one message serving two call sites that need to say different things.
 *
 * **Why 76 and not `GLineLimit.payloadBudget(n)`**: the line number is not known here. A person
 * typing a line does not know what `N` it will carry, because the transport assigns one at write
 * time - `PublisherQueue` reorders by priority, and a number assigned earlier would reach the wire
 * out of sequence. Spec 1.3 names 76 for exactly this case: a payload that fits 76 bytes still fits
 * a 96-byte buffer behind any `N` prefix and `*` field. Being conservative here is cheap and the
 * alternative is not - `GLineLimit`'s own doc records that an over-long line is "the one error a
 * resend cannot repair", since spec 8.5's recovery replays the block byte for byte, the replay
 * truncates at the same byte, is rejected again, and the host stops making progress.
 *
 * **Three things this deliberately does not do**, because the next reader will want to add them:
 *
 * - **It does not check that the line is a command any firmware has.** A terminal exists to explore
 *   a printer; `M851` may or may not be compiled in, and the machine's own
 *   `echo:Unknown command: "M851"` is the right answer from the only party that knows. (Measured
 *   against MarlinSim, 2026-11-18.)
 * - **It does not refuse a caller-supplied `N` word, nor `M110`, `M112`, `M28` or `M29`.** Those are
 *   host *transport* policy - they are about the send window and the command queue a layer above
 *   happens to keep - and `:gcode` must not know what sender its caller has. They are gated in
 *   `:backend:core`.
 * - **It does not parse the line.** Nothing is read, so `code/core`'s errors-as-values rule is not
 *   in play; this is a call site in `code/dsl`, where a bad argument throws.
 *
 * ```
 * rawBlock("M105")                 // "M105"
 * rawBlock("  M117 Hello World ")  // "M117 Hello World" - trimmed, spaces inside kept
 * rawBlock("NOTGCODE")             // "NOTGCODE" - not this function's business
 * GEncoder.frame(1, rawBlock("M105"))   // "N1 M105*38"
 *
 * rawBlock("   ")                  // throws: cannot be blank
 * rawBlock("M105 ; read it")       // throws: ';' at position 5
 * rawBlock("M105\nM112")           // throws: U+000A at position 4
 * rawBlock("M117 " + "A".repeat(72))   // throws: 77 bytes, 1 byte too long
 * ```
 *
 * @param line one command line as it was typed, with or without surrounding whitespace
 * @return the line trimmed - the bytes to send
 * @throws IllegalArgumentException when the line is blank, carries a character that would change
 *   the block it is framed into, or is longer than spec 1.3's interoperable payload
 */
fun rawBlock(line: String): String {
    val block = line.trim()
    require(block.isNotEmpty()) {
        "a raw G-code line cannot be blank - there is no block to send"
    }

    for (i in block.indices) {
        val c = block[i]
        val why = when {
            c == '\n' || c == '\r' ->
                "spec 1.2: it ends the line, so everything after it becomes a second command and " +
                    "the checksum lands on the wrong side of the break"
            c == ';' ->
                "spec 3.4a: it starts a comment, which swallows the checksum a transport appends"
            c == '*' ->
                "spec 8.3: it is the checksum delimiter, so the firmware would read what follows " +
                    "as the checksum"
            c < ' ' || c > '~' ->
                "spec 1.1: the wire carries printable ASCII, 0x20 to 0x7E, and this is outside it"
            else -> null
        }
        require(why == null) {
            val shown = if (c >= ' ' && c <= '~') "'$c'" else "U+%04X".format(c.code)
            "a raw G-code line cannot contain $shown at position $i - $why."
        }
    }

    val over = GLineLimit.overrun(block, GLineLimit.INTEROPERABLE_PAYLOAD)
    require(over == 0) {
        "a raw G-code line may be at most ${GLineLimit.INTEROPERABLE_PAYLOAD} bytes on the wire " +
            "(spec 1.3), and this one is ${GLineLimit.wireLength(block)} - $over " +
            (if (over == 1) "byte" else "bytes") + " too long. The transport prepends the N word " +
            "and appends the checksum, and an over-long block is the one error a resend cannot " +
            "repair (spec 8.5)."
    }

    return block
}
