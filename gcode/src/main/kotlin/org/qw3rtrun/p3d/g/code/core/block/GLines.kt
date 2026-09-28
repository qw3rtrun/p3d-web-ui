package org.qw3rtrun.p3d.g.code.core.block

import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GToken

/**
 * A line, as the liner read it off the token stream (GCODE_spec.md section 5).
 *
 * **A line knows tokens and nothing else.** Its shape is decided from two positions - the first
 * identifier and the last `*` - and both are found by walking tokens, so the line layer never builds
 * a word and never has to say what a word *is*. Words are the command layer's vocabulary
 * (the DSL builds them, `GEncoder` writes them), and a consumer that only routes, resends or
 * re-prints lines never pays for them.
 *
 * [raw] is every token, in wire order. It is the stored, primary value of every line type, which is
 * what makes "a line reproduces its input" true by construction rather than by an override that a
 * decomposing type has to remember to write - the mistake that once re-printed `N1 G28*12` as
 * ` G28`.
 *
 * ```
 * val line = GTokenizer.lines("N1 G28*18\n").first()
 * line.raw.joinToString("") { it.rawText() }    // "N1 G28*18\n"
 * line.body.joinToString("") { it.rawText() }   // " G28"
 * ```
 *
 * @property raw every token of the line, in wire order - leading whitespace and the terminator
 *   included
 * @property body the tokens a command may be read from: the whole line, except for a framed one,
 *   where it is what sits between the line-number field and the checksum marker
 */
sealed interface GLine {

    val raw: List<GToken>

    val body: List<GToken>
        get() = raw
}

/**
 * A line that carries a line number (spec 7): a verified packet, or one whose checksum failed.
 *
 * ```
 * (GTokenizer.lines("N1 G28*18\n").first() as GOrdered).number   // GInt(1)
 * ```
 *
 * @property number the line number, with its lexeme
 */
sealed interface GOrdered : GLine {
    val number: GInt
}

/**
 * A line whose checksum is present and **verified** (spec 8) - a consumer matching on this interface
 * is asking for lines it can trust.
 *
 * ```
 * (GTokenizer.lines("N1 G28*18\n").first() as GCheckSumControlled).checksum   // GInt(18)
 * ```
 *
 * @property checksum the checksum field's value, with its lexeme
 */
sealed interface GCheckSumControlled : GLine {
    val checksum: GInt
}

/**
 * A line with **no field at all**: its tokens contain no identifier (spec section 5).
 *
 * That is every no-op line - empty, whitespace, a comment - which is what section 5 names. It is
 * also a line of nothing but values or unlexable bytes: `?`, `42 99`, `"abc"` have no field either,
 * and `GLiner` classifies on the first identifier, so they arrive here too. The KDoc used to say
 * "whitespace and/or comments", which is narrower than the rule the liner applies and left a
 * consumer matching on this type - `GCodeReader` does - believing a line of corrupt bytes was a
 * no-op.
 *
 * Spec section 9 would call the second group an error, and an error type for it was declared here
 * for a while and never produced. Producing one is a real option; it is not taken because nothing
 * would act on the distinction, and a type nobody consumes is how the last one got stranded. Give
 * a transport a reason to answer a garbled unnumbered line differently from a blank one and it
 * becomes worth having.
 *
 * It keeps its tokens so the line still reproduces its input.
 *
 * ```
 * GTokenizer.lines("; comment\n").first() is GMeaninglessLine   // true
 * GTokenizer.lines("?\n").first() is GMeaninglessLine           // true, and not a no-op
 * ```
 */
data class GMeaninglessLine(override val raw: List<GToken>) : GLine

/**
 * A line with fields and **no framing**: neither a line number nor a checksum (spec 7.2 makes both
 * optional). The ordinary line of a G-code file, and of Marlin's default over a link.
 *
 * ```
 * GTokenizer.lines("G28\n").first() is GSimpleLine   // true
 * ```
 */
data class GSimpleLine(override val raw: List<GToken>) : GLine

/**
 * A framed line: `N<n> <body>*<cs>`, spec sections 7 and 8.
 *
 * The only line type that **decomposes** its input, and it does so by pointing into it: [body] is a
 * slice of [raw] running from just past the line number to the marker, so the two cannot disagree
 * and nothing is copied.
 *
 * Where the slice ends is a rule, not a detail. Spec section 5 puts the `*` field last, and Marlin
 * terminates the command at the marker (`get_serial_commands` writes a `\0` over it), so
 * `N1 G28*18 G1 X5` is **one** command - the `G1` is outside the frame and outside the checksum.
 *
 * **A [GPacketLine] is only ever built for a line whose checksum has been verified** (spec section
 * 8), so holding one means the line is intact; there is no `verify()` for a caller to forget.
 *
 * ```
 * val line = GTokenizer.lines("N1 G28*18 G1 X5\n").first() as GPacketLine
 * line.body.joinToString("") { it.rawText() }   // " G28" - the G1 is outside the frame
 * ```
 */
data class GPacketLine(
    override val number: GInt,
    override val checksum: GInt,
    override val body: List<GToken>,
    override val raw: List<GToken>,
) : GLine, GOrdered, GCheckSumControlled

/**
 * A line the liner could classify but not accept: a structural fault of framing (spec 7.1, 7.3) or of
 * the checksum (spec 8). Errors are values - the line still carries every token it was read from.
 *
 * ```
 * (GTokenizer.lines("N1 G28\n").first() as GError).msg   // "line number 1 has no checksum"
 * ```
 *
 * @property msg a human-readable statement of the fault
 */
sealed interface GError : GLine {
    val msg: String
}

/**
 * Spec section 7.3: a line number without a checksum.
 *
 * Over a serial link this is a structural error and the firmware rejects the line
 * (`Error:No Checksum with line number`). In a file it is harmless and fairly common, which is why
 * the liner reports the structure and leaves the severity to the caller rather than refusing to
 * parse. The corpus fixture contains two such lines.
 *
 * ```
 * GTokenizer.lines("N1 G28\n").first()   // GMissingChecksum, number 1
 * ```
 */
data class GMissingChecksum(val number: GInt?, override val raw: List<GToken>) : GError {
    override val msg: String
        get() = "line number ${number?.rawText() ?: "?"} has no checksum"
}

/**
 * Spec section 7.3: a checksum without a line number.
 *
 * ```
 * GTokenizer.lines("G28*18\n").first()   // GMissingLineNumber
 * ```
 */
data class GMissingLineNumber(override val raw: List<GToken>) : GError {
    override val msg: String
        get() = "checksum without a line number"
}

/**
 * Spec section 7.1: `N` is present and paired with a `*`, but is not followed by an integer.
 *
 * ```
 * GTokenizer.lines("N G28*18\n").first()   // GMalformedLineNumber
 * ```
 */
data class GMalformedLineNumber(override val raw: List<GToken>) : GError {
    override val msg: String
        get() = "'N' is not followed by a line number"
}

/**
 * Spec section 8.1: `*` is paired with an `N`, but what follows it is not a well-formed checksum
 * field. Two shapes, both decided on syntax alone and before any algorithm runs:
 *
 * - nothing usable after the marker - `N1*`, `*ABC`, `*10.5`;
 * - an integer of a width no algorithm claims. Section 8.1 gives the digit count the job of choosing
 *   between them, so 1-3 digits and 5 digits are checksum fields and **4, 6 or more are not**.
 *   `*1234` is not a mismatch: there is nothing to compare it against.
 *
 * ```
 * GTokenizer.lines("N1 G28*ABC\n").first()    // GMalformedChecksum
 * GTokenizer.lines("N1 G28*1234\n").first()   // GMalformedChecksum
 * ```
 */
data class GMalformedChecksum(val number: GInt, override val raw: List<GToken>) : GError {
    override val msg: String
        get() = "'*' is not followed by a checksum value on line ${number.rawText()}"
}

/**
 * Spec section 8.5: the line is framed correctly and its checksum field is well formed, but the
 * value on the wire is not the value the bytes produce. The line is corrupt in transit and a host
 * answers it with `Resend: <number>`.
 *
 * [expected] is recomputed from the covered bytes, [received] is what the wire carried. Both are
 * kept because a host's diagnostic quotes them together, and because the pair is what tells a
 * genuine corruption from a generator that is checksumming the wrong byte range.
 *
 * It keeps the whole token list, like every other line type, so it round-trips for free. [GOrdered]
 * because the resend request is addressed by line number. Deliberately **not**
 * [GCheckSumControlled]: a consumer matching on that interface is asking for lines it can trust.
 *
 * ```
 * (GTokenizer.lines("N1 G28*19\n").first() as GCheckSumFailedLine).msg
 * // "checksum mismatch on line 1: computed 18, received 19"
 * ```
 */
data class GCheckSumFailedLine(
    override val number: GInt,
    val expected: GInt,
    val received: GInt,
    override val raw: List<GToken>,
) : GError, GOrdered {
    override val msg: String
        get() = "checksum mismatch on line ${number.rawText()}: " +
                "computed ${expected.rawText()}, received ${received.rawText()}"
}
