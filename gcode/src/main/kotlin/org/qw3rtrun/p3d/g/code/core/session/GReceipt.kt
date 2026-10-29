package org.qw3rtrun.p3d.g.code.core.session

import org.qw3rtrun.p3d.g.code.core.block.GBlock

/**
 * What a [GCodeReader] concluded about one block, per GCODE_spec.md sections 7.2 and 8.5.
 *
 * Three outcomes, not two. A block is executed, or it is **silently discarded**, or it is rejected
 * with a resend request - and the middle one is not a rounding of either neighbour: spec 7.2 has the
 * firmware drop a repeated block without complaining, because complaining about it would ask for a
 * resend of a block already in flight.
 *
 * ```
 * when (val receipt = reader.read(block)) {
 *     is GAccepted -> execute(receipt.block)
 *     is GDuplicate -> {}
 *     is GRejected -> send(receipt.msg + "\nResend: " + receipt.resend)
 * }
 * ```
 *
 * @property block the block the verdict is about, with every token it was read from
 */
sealed interface GReceipt {
    val block: GBlock
}

/**
 * The block is intact, in sequence, and is the caller's to execute.
 *
 * [number] is the line number it consumed, or `null` for a block that consumes none - spec 5: "a line
 * number is only consumed by a line that is actually transmitted", so a blank or comment-only block
 * is accepted without moving the counter.
 *
 * ```
 * GCodeReader().read(GBlockReader.blocks("N1 G28*18\n").first())   // GAccepted(number = 1)
 * GCodeReader().read(GBlockReader.blocks("; c\n").first())         // GAccepted(number = null)
 * ```
 */
data class GAccepted(override val block: GBlock, val number: Int?) : GReceipt

/**
 * Spec 7.2: the block repeats the last accepted number, or the one before it. **Discard it and say
 * nothing** - this is not an error and must not produce a resend request.
 *
 * The case is created by the resend protocol itself. A host that has already retransmitted when the
 * firmware's `Resend:` arrives sends the same block twice; answering the second copy with another
 * resend request would not converge. Marlin's `queue.cpp` spells it
 * `if (WITHIN(gcode_N, serial.last_N - 1, serial.last_N)) continue;` and the comment above it reads
 * "A request-for-resend line was already in transit so we got two - oops!".
 *
 * ```
 * val reader = GCodeReader()
 * reader.read(GBlockReader.blocks("N1 G28*18\n").first())
 * reader.read(GBlockReader.blocks("N1 G28*18\n").first())   // GDuplicate(number = 1, lastLine = 1)
 * ```
 */
data class GDuplicate(override val block: GBlock, val number: Int, val lastLine: Int) : GReceipt

/**
 * The block is refused. Spec 8.5: the answer on the wire is [msg] followed by `Resend: `[resend].
 *
 * [lastLine] is the last number accepted, which is what both the message and the resend point are
 * computed from - a rejected block never moves the counter.
 *
 * ```
 * val receipt = GCodeReader().read(GBlockReader.blocks("N3 G28*16\n").first()) as GRejected
 * receipt.msg      // "Error:Line Number is not Last Line Number+1, Last Line: 0"
 * receipt.resend   // 1
 * ```
 *
 * @property lastLine the last line number the session accepted
 * @property msg the error line to answer with, in Marlin's wording
 * @property resend spec 8.5 step 4: the number to ask the host to rewind to, one past [lastLine]
 */
sealed interface GRejected : GReceipt {
    val lastLine: Int
    val msg: String

    val resend: Int
        get() = lastLine + 1
}

/**
 * Spec 7.2: a gap, or a number too far behind to be the resend race of [GDuplicate].
 *
 * ```
 * GCodeReader().read(GBlockReader.blocks("N3 G28*16\n").first())   // GOutOfSequence(number = 3, lastLine = 0)
 * ```
 */
data class GOutOfSequence(
    override val block: GBlock,
    val number: Int,
    override val lastLine: Int,
) : GRejected {
    override val msg: String
        get() = "Error:Line Number is not Last Line Number+1, Last Line: $lastLine"
}

/**
 * Spec 8: the checksum field is well formed and does not match the bytes.
 *
 * ```
 * GCodeReader().read(GBlockReader.blocks("N1 G28*19\n").first())   // GCorrupted(expected = 18, received = 19)
 * ```
 */
data class GCorrupted(
    override val block: GBlock,
    val expected: Int,
    val received: Int,
    override val lastLine: Int,
) : GRejected {
    override val msg: String
        get() = "Error:checksum mismatch, Last Line: $lastLine"
}

/**
 * Spec 7.3: a line number with no checksum.
 *
 * ```
 * GCodeReader().read(GBlockReader.blocks("N1 G28\n").first()).msg
 * // "Error:No Checksum with line number, Last Line: 0"
 * ```
 */
data class GUnchecksummed(override val block: GBlock, override val lastLine: Int) : GRejected {
    override val msg: String
        get() = "Error:No Checksum with line number, Last Line: $lastLine"
}

/**
 * Spec 7.3: a checksum with no line number, and - under [GNumbering.REQUIRED] - a block carrying
 * neither when the session demands both.
 *
 * ```
 * GCodeReader().read(GBlockReader.blocks("G28*18\n").first())                    // GUnnumbered
 * GCodeReader(GNumbering.REQUIRED).read(GBlockReader.blocks("G28\n").first())   // GUnnumbered
 * ```
 */
data class GUnnumbered(override val block: GBlock, override val lastLine: Int) : GRejected {
    override val msg: String
        get() = "Error:No Line Number with checksum, Last Line: $lastLine"
}

/**
 * Spec 9: the block did not parse into a shape this layer can judge - `N1 G28*ABC`, `N G28*18`.
 *
 * Kept separate from [GCorrupted] rather than folded into it, because the two are different faults
 * even though a host answers both with a resend: a mismatch means the bytes arrived wrong, while
 * this means they did not form a block. Marlin cannot tell them apart - `strtol("ABC")` is 0, which
 * it then reports as a checksum mismatch - and losing that distinction is a cost of parsing with
 * `strtol`, not a decision worth copying.
 *
 * ```
 * GCodeReader().read(GBlockReader.blocks("N1 G28*ABC\n").first()).msg
 * // "Error:checksum is not a number, Last Line: 0"
 * ```
 */
data class GMalformed(
    override val block: GBlock,
    val detail: String,
    override val lastLine: Int,
) : GRejected {
    override val msg: String
        get() = "Error:$detail, Last Line: $lastLine"
}
