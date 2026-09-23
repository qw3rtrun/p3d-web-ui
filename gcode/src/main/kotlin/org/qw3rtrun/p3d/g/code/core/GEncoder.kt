package org.qw3rtrun.p3d.g.code.core

import org.qw3rtrun.p3d.g.code.core.token.GBlock
import org.qw3rtrun.p3d.g.code.core.token.GBlockPart
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GComment
import org.qw3rtrun.p3d.g.code.core.token.GFlagWord
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GUnnamedStr
import org.qw3rtrun.p3d.g.code.core.token.GWord

/**
 * Emits wire-ready text: a command (GCODE_spec.md section 4), and the `N`/`*` framing around it
 * (sections 7 and 8).
 *
 * **It produces a `String`, not a `List<GToken>`, and that is the design.** The checksum covers the
 * bytes as transmitted (section 8.3), so whitespace is part of the output rather than something a
 * later stage inserts. The encoder this replaces - `GCommand.print()` - returned tokens and let its
 * caller join them, which is why it could emit a line whose bytes depended on the values happening
 * to be self-delimiting (finding 1.10). A byte-oriented encoder cannot have that bug, and it is also
 * the only shape a checksum can be computed over.
 *
 * **Not driven by a descriptor.** *Which* fields a command should carry, and what they default to,
 * is a validation question and belongs one layer above this. Emitting bytes is a separate job, and
 * keeping it separate is what lets the encoder write a command nothing has described. A `GDescriptor`
 * type was carried here for a while against a defaulting layer that was never built and a Java
 * consumer that no longer exists; it is deleted, and git history has it if that layer arrives.
 *
 * Stateless and allocation-light: one `StringBuilder` per line.
 */
object GEncoder {

    /**
     * One command as text: the command word, then each parameter, **separated by a single space**.
     *
     * Canonical, not reproductive. A parsed word carries the exact bytes it was read from in its
     * `raw` - `X  10` is one word holding two spaces - and this deliberately ignores that and
     * renders the field instead. An encoder that reproduced input spacing would emit a different
     * number of bytes for the same command depending on where it came from, and the checksum would
     * follow. Use `GLine.raw()` when the original bytes are what is wanted.
     *
     * Within a word there is no separator: the identifier is followed immediately by its value, so
     * a value's lexeme is what reaches the wire (`X10.50` stays `X10.50`, see `GNumber`).
     */
    fun encode(command: GCommand): String {
        val out = StringBuilder()
        appendCommand(out, command)
        return out.toString()
    }

    /**
     * A whole line: every part in order, **separated by a single space**, per spec section 5.
     *
     * A comment renders through its own `rawText()`, so the caller's choice of `;` or `( )` and of
     * whether to leave a space after the marker is preserved - `GTailComment(" move")` is `; move`
     * and `GTailComment("move")` is `;move`. Both are legal (section 6) and the difference is the
     * author's, not the encoder's.
     */
    fun encode(block: GBlock): String {
        val out = StringBuilder()
        for (i in block.parts.indices) {
            if (i > 0) out.append(' ')
            appendPart(out, block.parts[i])
        }
        return out.toString()
    }

    /**
     * A framed line built from a whole [block]: `N<number> <payload>*<checksum> <trailing comment>`.
     *
     * **A trailing comment goes after the checksum and is not covered by it** (section 5 puts the
     * `*` field last before any comment, and section 8.3 excludes what follows it). A comment that
     * is *not* trailing - one sitting between two commands - is part of the payload and **is**
     * covered, because those are the bytes that get transmitted before the `*`.
     *
     * So the split is positional: everything up to and including the last non-comment part is
     * payload, and only the run of comments after it is appended past the marker. A block that is
     * nothing but comments has no payload and cannot be framed - there is nothing to acknowledge or
     * resend - so it comes back as its own text, unnumbered.
     */
    fun frame(number: Int, block: GBlock, checksum: CheckSumCalculator = XorCheckSum()): String {
        var lastPayload = -1
        for (i in block.parts.indices) if (block.parts[i] !is GComment) lastPayload = i
        if (lastPayload < 0) return encode(block)

        val out = StringBuilder()
        out.append('N').append(number)
        for (i in 0..lastPayload) {
            out.append(' ')
            appendPart(out, block.parts[i])
        }

        val covered = out.toString()
        checksum.add(covered)
        out.append('*').append(checksum.get().lexeme)

        for (i in lastPayload + 1 until block.parts.size) {
            out.append(' ')
            appendPart(out, block.parts[i])
        }
        return out.toString()
    }

    private fun appendPart(out: StringBuilder, part: GBlockPart) {
        when (part) {
            is GCommand -> appendCommand(out, part)
            is GComment -> out.append(part.rawText())
        }
    }

    /** The command word, then each parameter behind a single space. */
    private fun appendCommand(out: StringBuilder, command: GCommand) {
        appendWord(out, command.head)
        for (i in command.params.indices) {
            out.append(' ')
            appendWord(out, command.params[i])
        }
    }

    /**
     * A framed line around one command: `N<number> <command>*<checksum>`, sections 7 and 8.
     *
     * The common case, and **the same function** as [frame] over a block - a one-command line is a
     * one-part block, and section 8.3's order of operations is stated and implemented there, once.
     * It was implemented twice until this became a delegation, which is two copies of the four
     * most order-sensitive lines in the encoder and a silent divergence waiting to happen.
     *
     * The algorithm is the caller's to choose by passing the calculator: [XorCheckSum] (section 8.2,
     * the default and what Marlin expects) or [Crc16CheckSum] (section 8.4, five digits, stronger).
     * A calculator carries the state of one line, so a fresh one is needed per call.
     */
    fun frame(number: Int, command: GCommand, checksum: CheckSumCalculator = XorCheckSum()): String =
        frame(number, GBlock(listOf(command)), checksum)

    /**
     * One field: the identifier, then its value with no separator between them.
     *
     * A [GUnnamedStr] has no identifier to write - `GEmptyId` renders as nothing - and its value is
     * spec 3.4a's bare rest-of-line string, so it reaches the wire undelimited and, being the rest
     * of the line, only ever last. Nothing here enforces that position: a command carrying one is
     * built by the command that documents it, and [encode]'s single space before each field is what
     * separates it from the lettered parameters in front.
     */
    private fun appendWord(out: StringBuilder, word: GWord) {
        out.append(word.id.rawText())
        when (word) {
            is GParameterWord<*> -> out.append(word.value.rawText())
            is GUnnamedStr -> out.append(word.str.rawText())
            is GFlagWord -> {}
        }
    }
}
