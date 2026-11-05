package org.qw3rtrun.p3d.g.code.core.block

import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GToken

/**
 * A block, as the reader read it off the token stream (GCODE_spec.md section 5).
 *
 * A block knows tokens and nothing else: its shape is decided from two positions - the first
 * identifier and the last `*` - found by walking tokens, so this layer never builds or names a word.
 * Words are the command layer's vocabulary (the DSL builds them, `GEncoder` writes them).
 *
 * Spec 5 calls it a "line (block)"; this layer calls it a block and leaves "line" to the things that
 * really are lines of text - the `GLineBreak` that ends one, the line number of section 7, and the
 * characters the lexer reads through. A block that was read is this type, carrying tokens; a block
 * being built is a `GBlockDraft` one layer up, carrying parts - the link is not a KDoc one, since this
 * layer may not name the layer above it and `GLayeringTest` reads KDoc as source.
 *
 * [raw] is every token, in wire order, and the stored primary value of every block type - which is
 * what makes "a block reproduces its input" true by construction, not by an override a decomposing
 * type has to remember to write (the mistake that once re-printed `N1 G28*12` as ` G28`).
 *
 * ```
 * val block = GBlockReader.blocks("N1 G28*18\n").first()
 * block.raw.joinToString("") { it.rawText() }    // "N1 G28*18\n"
 * block.body.joinToString("") { it.rawText() }   // " G28"
 * ```
 *
 * @property raw every token of the block, in wire order - leading whitespace and the terminator
 *   included
 * @property body the tokens a command may be read from: the whole block, except for a framed one,
 *   where it is what sits between the line-number field and the checksum marker
 */
sealed interface GBlock {

    val raw: List<GToken>

    val body: List<GToken>
        get() = raw
}

/**
 * A block that carries a line number (spec 7) - the question "can this block be asked for again?".
 *
 * Four types answer to it: a block whose checksum verified, and the three faults that still name
 * their line. Spec 8.5 gives all three the same answer, a `Resend:` for that number - which is why
 * this is the partition rather than a property of the successful case. See [GError] for the rest.
 *
 * @property number the line number, with its lexeme
 */
sealed interface GOrdered : GBlock {
    val number: GInt
}

/**
 * A block whose checksum is present and verified (spec 8) - matching on this interface is asking for
 * blocks that can be trusted.
 *
 * @property checksum the checksum field's value, with its lexeme
 */
sealed interface GCheckSumControlled : GBlock {
    val checksum: GInt
}

/**
 * A block with no field at all: its tokens contain no identifier (spec 5).
 *
 * Every no-op block - empty, whitespace, a comment - lands here, and so does unlexable content with
 * no identifier (`?`, `42 99`, `"abc"`), since `GBlockReader` classifies on the first identifier. The
 * KDoc used to say "whitespace and/or comments", narrower than the rule actually applied, and
 * `GCodeReader` matched on this type believing a block of corrupt bytes was a no-op.
 *
 * A separate error type for the corrupt-but-unnumbered case was declared here once and never
 * produced; not reinstated, because nothing acts on the distinction yet - a type nobody consumes is
 * how the last one got stranded.
 *
 * It keeps its tokens so the block still reproduces its input.
 *
 * ```
 * GBlockReader.blocks("; comment\n").first() is GMeaninglessBlock   // true
 * GBlockReader.blocks("?\n").first() is GMeaninglessBlock           // true, and not a no-op
 * ```
 */
data class GMeaninglessBlock(override val raw: List<GToken>) : GBlock

/**
 * A block with fields and **no framing**: neither a line number nor a checksum (spec 7.2 makes both
 * optional). The ordinary block of a G-code file, and of Marlin's default over a link.
 *
 * ```
 * GBlockReader.blocks("G28\n").first() is GPlainBlock   // true
 * ```
 */
data class GPlainBlock(override val raw: List<GToken>) : GBlock

/**
 * A framed block: `N<n> <body>*<cs>`, spec 7-8.
 *
 * The only block type that decomposes its input, and does so by pointing into it: [body] is a slice
 * of [raw] from just past the line number to the marker, so the two cannot disagree and nothing is
 * copied.
 *
 * Where the slice ends is a rule, not a detail: spec 5 puts the `*` field last, and Marlin terminates
 * the command at the marker (`get_serial_commands` writes a `\0` over it), so `N1 G28*18 G1 X5` is
 * one command - the `G1` is outside the frame and the checksum.
 *
 * Only ever built for a block whose checksum has been verified (spec 8), so holding one means the
 * block is intact - there is no `verify()` to forget.
 *
 * ```
 * val block = GBlockReader.blocks("N1 G28*18 G1 X5\n").first() as GFramedBlock
 * block.body.joinToString("") { it.rawText() }   // " G28" - the G1 is outside the frame
 * ```
 */
data class GFramedBlock(
    override val number: GInt,
    override val checksum: GInt,
    override val body: List<GToken>,
    override val raw: List<GToken>,
) : GBlock, GOrdered, GCheckSumControlled

/**
 * A block the reader could classify but not accept: a structural fault of framing (spec 7.1, 7.3) or of
 * the checksum (spec 8). Errors are values - the block still carries every token it was read from.
 *
 * The faults partition by what a host can do about them, and the line is [GOrdered], not the fault's
 * name:
 *
 * - addressable - the block names its line, so spec 8.5's answer is `Resend: n`:
 *   [GCheckSumFailedBlock] (the value disagrees), [GMalformedChecksum] (the field is garbled) and
 *   [GMissingChecksum] (spec 7.3, no field at all).
 * - not addressable - nothing on the block names a line, so the way back into sequence is `M110`
 *   (spec 7.2): [GMissingLineNumber] and [GMalformedLineNumber].
 *
 * All three addressable ones must implement [GOrdered] - a consumer once switched on it for "resend
 * line n" and silently dropped two of the three because only the mismatch case did.
 *
 * @property msg a human-readable statement of the fault
 */
sealed interface GError : GBlock {
    val msg: String
}

/**
 * Spec 7.3: a line number without a checksum.
 *
 * Over a serial link this is a structural error and the firmware rejects the block
 * (`Error:No Checksum with line number`). In a file it is harmless and fairly common - the corpus
 * fixture has two - so the reader reports the structure and leaves the severity to the caller.
 *
 * [GOrdered], because the block names its line regardless of whether a checksum was attached.
 */
data class GMissingChecksum(override val number: GInt, override val raw: List<GToken>) : GError, GOrdered {
    override val msg: String
        get() = "line number ${number.rawText()} has no checksum"
}

/** Spec 7.3: a checksum without a line number. */
data class GMissingLineNumber(override val raw: List<GToken>) : GError {
    override val msg: String
        get() = "checksum without a line number"
}

/** Spec 7.1: `N` is present and paired with a `*`, but is not followed by an integer. */
data class GMalformedLineNumber(override val raw: List<GToken>) : GError {
    override val msg: String
        get() = "'N' is not followed by a line number"
}

/**
 * Spec 8.1: `*` is paired with an `N`, but what follows it is not a well-formed checksum field. Two
 * shapes, decided on syntax alone before any algorithm runs: nothing usable after the marker (`N1*`,
 * `*ABC`, `*10.5`), or an integer of a width no algorithm claims - section 8.1 gives the digit count
 * the job of choosing, so 1-3 or 5 digits are checksum fields and 4, 6 or more are not. `*1234` is
 * not a mismatch: there is nothing to compare it against.
 *
 * [GOrdered]: the line is named, so spec 8.5's answer is a resend of this block, same as a mismatch.
 *
 * ```
 * GBlockReader.blocks("N1 G28*ABC\n").first()    // GMalformedChecksum
 * GBlockReader.blocks("N1 G28*1234\n").first()   // GMalformedChecksum
 * ```
 */
data class GMalformedChecksum(override val number: GInt, override val raw: List<GToken>) : GError, GOrdered {
    override val msg: String
        get() = "'*' is not followed by a checksum value on line ${number.rawText()}"
}

/**
 * Spec 8.5: the block is framed correctly and its checksum field is well formed, but the value on
 * the wire is not the value the bytes produce - corrupt in transit, answered with `Resend: <number>`.
 *
 * [expected] is recomputed from the covered bytes, [received] is what the wire carried; both are kept
 * because a diagnostic quotes them together, and the pair tells genuine corruption from a generator
 * checksumming the wrong byte range.
 *
 * [GOrdered] because the resend is addressed by line number. Deliberately not
 * [GCheckSumControlled]: matching on that interface is asking for blocks that can be trusted.
 *
 * ```
 * (GBlockReader.blocks("N1 G28*19\n").first() as GCheckSumFailedBlock).msg
 * // "checksum mismatch on line 1: computed 18, received 19"
 * ```
 */
data class GCheckSumFailedBlock(
    override val number: GInt,
    val expected: GInt,
    val received: GInt,
    override val raw: List<GToken>,
) : GError, GOrdered {
    override val msg: String
        get() = "checksum mismatch on line ${number.rawText()}: " +
                "computed ${expected.rawText()}, received ${received.rawText()}"
}
