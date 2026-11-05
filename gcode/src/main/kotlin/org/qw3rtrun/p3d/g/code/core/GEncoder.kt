package org.qw3rtrun.p3d.g.code.core

import org.qw3rtrun.p3d.g.code.core.block.CheckSumCalculator
import org.qw3rtrun.p3d.g.code.core.block.XorCheckSum
import org.qw3rtrun.p3d.g.code.core.command.GBlockDraft
import org.qw3rtrun.p3d.g.code.core.command.GBlockPart
import org.qw3rtrun.p3d.g.code.core.command.GCommand
import org.qw3rtrun.p3d.g.code.core.command.GCommentPart
import org.qw3rtrun.p3d.g.code.core.command.GFlagWord
import org.qw3rtrun.p3d.g.code.core.command.GParameterWord
import org.qw3rtrun.p3d.g.code.core.command.GUnnamedStr
import org.qw3rtrun.p3d.g.code.core.command.GWord

/**
 * Emits wire-ready text: a command (spec 4), and the `N`/`*` framing around it (spec 7-8).
 *
 * Produces a `String`, not a `List<GToken>` - the checksum covers the bytes as transmitted (spec
 * 8.3), so whitespace is part of the output rather than something a later stage inserts. The
 * previous encoder returned tokens and let the caller join them, which let a block's bytes depend on
 * whether its values happened to be self-delimiting (finding 1.10); a byte-oriented encoder cannot
 * have that bug.
 *
 * Not driven by a descriptor: which fields a command carries is a validation question for a layer
 * above this one; emitting bytes is a separate job.
 *
 * One framer, and its primitive takes text: [frame] over a `String` payload implements spec 7-8
 * once; the command and draft overloads render their bytes and hand them to it, because a sender
 * holding only rendered text (the transport assigns the line number last, after reordering) would
 * otherwise have to re-parse it.
 *
 * The text payload is transmitted verbatim and never inspected - validating it would mean parsing,
 * which is `GBlockReader`'s job. Two things the caller owns: a payload must be one line, and must not
 * open a comment it does not close, since either swallows the checksum field. A payload already
 * carrying a `*` frames correctly regardless, since spec 8.3's last-`*`-wins rule makes the framer's
 * own marker the one that counts.
 *
 * Stateless and allocation-light: one `StringBuilder` per block.
 *
 * ```
 * val g28 = GCommand(GLetter('G'), GInt(28))
 * GEncoder.encode(g28)       // "G28"
 * GEncoder.frame(1, g28)     // "N1 G28*18"
 * GEncoder.frame(1, "G28")   // "N1 G28*18" - the same bytes, from rendered text
 * ```
 */
object GEncoder {

    /**
     * One command as text: the command word, then each parameter, **separated by a single space**.
     *
     * Canonical, not reproductive - a parsed word's raw bytes (`X  10`, two spaces) are ignored in
     * favor of rendering the field, since reproducing input spacing would make the checksum depend
     * on where the command came from. Use `GBlock.raw()` for the original bytes. Within a word there
     * is no separator: a value's lexeme reaches the wire immediately after its identifier (`X10.50`
     * stays `X10.50`).
     *
     * A [GUnnamedStr] has no identifier - it is spec 3.4a's bare rest-of-line string - so it reaches
     * the wire undelimited; nothing here enforces that it comes last, which is the command's job.
     *
     * ```
     * val x = GParameterWord(GLetter('X'), GFloat("10.50"))
     * GEncoder.encode(GCommand(GLetter('G'), GInt(1), listOf(x)))   // "G1 X10.50"
     * ```
     *
     * @param command the command to render
     * @return the command's text, without a terminator
     */
    fun encode(command: GCommand): String {
        val out = StringBuilder()
        appendCommand(out, command)
        return out.toString()
    }

    /**
     * A whole block: every part in order, **separated by a single space**, per spec section 5.
     *
     * A `GCommentPart` renders through its comment's own `rawText()`, so the caller's choice of `;`
     * or `( )`, and of a space after the marker, is preserved.
     *
     * ```
     * val g1 = GCommand(GLetter('G'), GInt(1), listOf(GParameterWord(GLetter('X'), GFloat("10.50"))))
     * GEncoder.encode(GBlockDraft(g1, GCommentPart(GTailComment(" move"))))   // "G1 X10.50 ; move"
     * ```
     *
     * @param draft the block's parts, in order
     * @return the block's text, unframed and without a terminator
     */
    fun encode(draft: GBlockDraft): String {
        val out = StringBuilder()
        for (i in draft.parts.indices) {
            if (i > 0) out.append(' ')
            appendPart(out, draft.parts[i])
        }
        return out.toString()
    }

    /**
     * A framed block built from a whole [draft]: `N<number> <payload>*<checksum> <trailing comment>`.
     *
     * A trailing comment goes after the checksum and is not covered by it (spec 5 puts `*` last
     * before any comment; spec 8.3 excludes what follows it). A comment that is not trailing -
     * sitting between two commands - is part of the payload and is covered. The split is positional:
     * everything up to and including the last non-comment part is payload; a block that is nothing
     * but comments has no payload and comes back as its own text, unnumbered.
     *
     * ```
     * val g28 = GCommand(GLetter('G'), GInt(28))
     * GEncoder.frame(1, GBlockDraft(g28, GCommentPart(GTailComment(" home"))))   // "N1 G28*18 ; home"
     * GEncoder.frame(1, GBlockDraft(GCommentPart(GTailComment(" only"))))        // "; only" - nothing to frame
     * ```
     *
     * @param number the line number to put in the `N` field
     * @param draft the block's parts, in order
     * @param checksum a fresh calculator for the algorithm to use; [org.qw3rtrun.p3d.g.code.core.block.XorCheckSum] by default
     * @return the framed block, without a terminator
     */
    fun frame(number: Int, draft: GBlockDraft, checksum: CheckSumCalculator = XorCheckSum()): String {
        var lastPayload = -1
        for (i in draft.parts.indices) if (draft.parts[i] !is GCommentPart) lastPayload = i
        if (lastPayload < 0) return encode(draft)

        val payload = StringBuilder()
        for (i in 0..lastPayload) {
            if (i > 0) payload.append(' ')
            appendPart(payload, draft.parts[i])
        }

        val framed = frame(number, payload.toString(), checksum)
        if (lastPayload == draft.parts.size - 1) return framed

        val out = StringBuilder(framed)
        for (i in lastPayload + 1 until draft.parts.size) {
            out.append(' ')
            appendPart(out, draft.parts[i])
        }
        return out.toString()
    }

    /**
     * The framer itself, over bytes a caller has already rendered: `N<number> <payload>*<checksum>`,
     * spec 7-8.
     *
     * Three steps in spec 8.3's order: prepend `N<number> `, checksum exactly the bytes produced so
     * far (the `N`, digits and space included), then append `*` and the value - nothing added or
     * removed after. [payload] is not validated and not parsed; it reaches the wire byte for byte, so
     * a payload must be one line and must not open a comment it does not close - see this object's
     * class doc. An empty payload frames like any other, as `N<number> *<checksum>`.
     *
     * ```
     * GEncoder.frame(3, "T0")                    // "N3 T0*57"
     * GEncoder.frame(3, "T0", Crc16CheckSum())   // "N3 T0*06939"
     * ```
     *
     * @param number the line number to put in the `N` field
     * @param payload the block's bytes, as they are to be transmitted
     * @param checksum a fresh calculator for the algorithm to use; [XorCheckSum] by default
     * @return the framed block, without a terminator
     */
    fun frame(number: Int, payload: String, checksum: CheckSumCalculator = XorCheckSum()): String {
        val out = StringBuilder(payload.length + 18)
        out.append('N').append(number).append(' ').append(payload)

        val covered = out.toString()
        checksum.add(covered)
        out.append('*').append(checksum.get().lexeme)
        return out.toString()
    }

    private fun appendPart(out: StringBuilder, part: GBlockPart) {
        when (part) {
            is GCommand -> appendCommand(out, part)
            is GCommentPart -> out.append(part.comment.rawText())
        }
    }

    private fun appendCommand(out: StringBuilder, command: GCommand) {
        appendWord(out, command.head)
        for (i in command.params.indices) {
            out.append(' ')
            appendWord(out, command.params[i])
        }
    }

    /**
     * A framed block around one command: `N<number> <command>*<checksum>`, spec 7-8.
     *
     * The same function as [frame] over a payload string - the command is rendered by [encode] and
     * framed there, so spec 8.3's order of operations is implemented once.
     *
     * The algorithm is the caller's to choose: [XorCheckSum] (spec 8.2, Marlin's default) or
     * `Crc16CheckSum` (spec 8.4, five digits, stronger). A calculator carries one block's state, so a
     * fresh one is needed per call.
     *
     * ```
     * val g28 = GCommand(GLetter('G'), GInt(28))
     * GEncoder.frame(1, g28)                    // "N1 G28*18"
     * GEncoder.frame(1, g28, Crc16CheckSum())   // "N1 G28*14291"
     * ```
     *
     * @param number the line number to put in the `N` field
     * @param command the command to frame
     * @param checksum a fresh calculator for the algorithm to use; [XorCheckSum] by default
     * @return the framed block, without a terminator
     */
    fun frame(number: Int, command: GCommand, checksum: CheckSumCalculator = XorCheckSum()): String =
        frame(number, encode(command), checksum)

    private fun appendWord(out: StringBuilder, word: GWord) {
        when (word) {
            is GParameterWord<*> -> {
                out.append(word.id.rawText())
                out.append(word.value.rawText())
            }

            is GFlagWord -> out.append(word.id.rawText())
            is GUnnamedStr -> out.append(word.str.rawText())
        }
    }
}
