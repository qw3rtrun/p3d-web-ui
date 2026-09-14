package org.qw3rtrun.p3d.g.code.core

import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
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
 * **Not driven by `GDescription`.** The descriptor knows a command's fields and their defaults, and
 * that makes it the right source of truth for *which* fields a command should carry - a validation
 * and defaulting question, one layer above this. Emitting bytes is a separate job, and keeping it
 * separate is what lets the encoder handle a command the module has no descriptor for, which today
 * is nearly all of them. A defaulting layer can be built on top later and will call into this.
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
        appendWord(out, command.head)
        for (i in command.params.indices) {
            out.append(' ')
            appendWord(out, command.params[i])
        }
        return out.toString()
    }

    /**
     * A framed line: `N<number> <command>*<checksum>`, per sections 7 and 8.
     *
     * The order of operations is the whole of section 8.3 and is not interchangeable: the prefix is
     * prepended **first**, the finished byte string is checksummed **second**, and the `*` field is
     * appended **last**. The `N` and its digits are covered; the marker and everything after it are
     * not. Nothing touches the whitespace once the checksum is taken, and no space is emitted before
     * the marker - a space there would be covered and would change the value.
     *
     * The algorithm is the caller's to choose by passing the calculator: [XorCheckSum] (section 8.2,
     * the default and what Marlin expects) or [Crc16CheckSum] (section 8.4, five digits, stronger).
     * A calculator carries the state of one line, so a fresh one is needed per call.
     */
    fun frame(number: Int, command: GCommand, checksum: CheckSumCalculator = XorCheckSum()): String {
        val out = StringBuilder()
        out.append('N').append(number).append(' ').append(encode(command))

        val covered = out.toString()
        for (i in 0 until covered.length) checksum.add(covered[i])

        return out.append('*').append(checksum.get().lexeme).toString()
    }

    private fun appendWord(out: StringBuilder, word: GWord) {
        out.append(word.id.rawText())
        if (word is GParameterWord<*>) out.append(word.value.rawText())
    }
}
