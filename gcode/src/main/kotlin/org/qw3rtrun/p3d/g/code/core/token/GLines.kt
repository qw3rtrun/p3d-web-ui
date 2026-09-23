package org.qw3rtrun.p3d.g.code.core.token

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
 */
sealed interface GLine {

    /** Every token of the line, in wire order - leading whitespace and the terminator included. */
    val raw: List<GToken>

    /**
     * The tokens a command may be read from: the whole line, except for a framed one, where it is
     * what sits between the line-number field and the checksum marker.
     */
    val body: List<GToken>
        get() = raw
}

sealed interface GOrdered : GLine {
    val number: GInt
}

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
 */
data class GMeaninglessLine(override val raw: List<GToken>) : GLine

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
 */
data class GPacketLine(
    override val number: GInt,
    override val checksum: GInt,
    override val body: List<GToken>,
    override val raw: List<GToken>,
) : GLine, GOrdered, GCheckSumControlled

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
 */
data class GMissingChecksum(val number: GInt?, override val raw: List<GToken>) : GError {
    override val msg: String
        get() = "line number ${number?.rawText() ?: "?"} has no checksum"
}

/** Spec section 7.3: a checksum without a line number. */
data class GMissingLineNumber(override val raw: List<GToken>) : GError {
    override val msg: String
        get() = "checksum without a line number"
}

/** Spec section 7.1: `N` is present and paired with a `*`, but is not followed by an integer. */
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
