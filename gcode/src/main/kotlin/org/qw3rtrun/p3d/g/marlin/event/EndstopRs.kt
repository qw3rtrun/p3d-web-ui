package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.equalsAscii
import org.qw3rtrun.p3d.g.protocol.sameAscii
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * `M119`'s answer, which is **several lines**. Captured off MarlinSim (bugfix-2.1.x, Nov 18 2026)
 * by `GCodeLiveProbe`, which is also where the shape below is from:
 *
 * ```
 * Reporting endstop status
 * x_min: open
 * y_min: TRIGGERED
 * z_probe: open
 * ```
 *
 * **One interface over the family, as with [SdCardRs] and [SettingsRs]**, so a caller matching
 * `EndstopRs<*>` catches the header and every state line, and nothing else - one arm for the whole
 * report rather than one per type, and the next line kind lands in it for free.
 *
 * [GRsDecoder] reads one line, so the decoders here are per line - [EndstopReportHeader] and then
 * an [EndstopStateRs] each - but **a line is not an event**. [EndstopReportReader], owned by
 * `:backend:terminal`, folds the lines into one [EndstopReport] before anything above `:gcode` is
 * told about the family at all; the header and the state lines are decode-layer values and no
 * longer reach `PrinterState` individually. `M20`'s listing is read the same way, by
 * [SdFileListReader] - the difference is that it has a terminator line to stop on and `M119` has
 * none, so this family's reader is ended by the first reply that is not a member of it.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface EndstopRs<D : EndstopRs<D>> : GEventRs<D>

/**
 * `Reporting endstop status` - the heading `M119` writes ahead of the state lines.
 *
 * ```
 * EndstopReportHeader.decode("Reporting endstop status")   // EndstopReportHeader
 * ```
 */
object EndstopReportHeader : EndstopRs<EndstopReportHeader>, GRsDecoder<EndstopReportHeader> {

    override fun encode(): String = "Reporting endstop status"

    override fun decode(line: String): EndstopReportHeader? =
        if (line.equalsAscii("Reporting endstop status")) this else null
}

/**
 * One endstop's line: `x_min: open` or `z_probe: TRIGGERED`.
 *
 * The name is matched against the shapes Marlin actually writes rather than against `\w+`, because
 * `<word>: <word>` is far too common a shape on this wire to claim - `busy: processing` fits it.
 * Marlin's own set, from `Endstops::report_states`: `<axis>_min` / `<axis>_max` for axes `x`, `y`,
 * `z`, `a`..`w` and their duplicates `x2`, `y2`, `z2`, `z3`, `z4`, plus `z_probe`, `probe_en` and
 * `filament`.
 *
 * ```
 * EndstopStateRs.decode("x_min: TRIGGERED")     // EndstopStateRs("x_min", triggered = true)
 * EndstopStateRs.match("busy: processing")      // false
 * EndstopStateRs("z_probe", false).encode()     // "z_probe: open"
 * ```
 */
data class EndstopStateRs(val name: String, val triggered: Boolean) : EndstopRs<EndstopStateRs> {

    override fun encode(): String = "$name: " + if (triggered) TRIGGERED else OPEN

    companion object : GRsDecoder<EndstopStateRs> {

        private const val TRIGGERED = "TRIGGERED"
        private const val OPEN = "open"

        /** The three endstops Marlin names outright, rather than by axis. */
        private val FIXED = arrayOf("z_probe", "probe_en", "filament")

        private const val MIN = "_min"
        private const val MAX = "_max"

        /** `<name>: <state>`, or null when the line is not one. */
        override fun decode(line: String): EndstopStateRs? {
            val text = line.asciiTrim()

            val nameEnd = nameEnd(text)
            if (nameEnd < 0) return null
            if (nameEnd >= text.length || text[nameEnd] != ':') return null

            val state = text.substring(text.blanksEnd(nameEnd + 1))
            val triggered = when {
                state.sameAscii(TRIGGERED) -> true
                state.sameAscii(OPEN) -> false
                else -> return null
            }
            return EndstopStateRs(text.substring(0, nameEnd), triggered)
        }

        /**
         * Where the endstop's name ends, or -1 when [text] does not open with one. Two shapes, the
         * fixed names first because `z_probe` would otherwise be read as axis `z` and fail on the
         * suffix. The axis form is a letter, **one** optional character, and `_min`/`_max`: that
         * covers `x_min`, the duplicates `x2`, `z3`, `z4`, and whatever `a`..`w` a delta or an IDEX
         * reports.
         */
        private fun nameEnd(text: String): Int {
            for (fixed in FIXED) {
                if (text.startsWithAscii(fixed)) return fixed.length
            }

            if (text.isEmpty() || !isLetter(text[0])) return -1
            var i = 1
            if (i < text.length && (isLetter(text[i]) || isDigit(text[i]))) i++
            if (text.startsWithAscii(MIN, i) || text.startsWithAscii(MAX, i)) return i + MIN.length
            return -1
        }

        private fun isLetter(c: Char) = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

        private fun isDigit(c: Char) = c >= '0' && c <= '9'
    }
}

/**
 * Either line of an `M119` report.
 *
 * ```
 * EndstopRsDecoder.decode("y_max: open")   // EndstopStateRs("y_max", triggered = false)
 * ```
 */
object EndstopRsDecoder : GRsDecoder<EndstopRs<*>> {

    override fun decode(line: String): EndstopRs<*>? =
        EndstopReportHeader.decode(line) ?: EndstopStateRs.decode(line)
}

/**
 * A whole `M119` answer, assembled: every endstop the machine named, in the order it named them.
 *
 * Produced only by an [EndstopReportReader], and the only shape of this family anything above
 * `:gcode` sees - [EndstopReportHeader] and [EndstopStateRs] are decode-layer values that the
 * reader folds away.
 *
 * **[encode] writes the whole block**, header included, which makes it the second reply in this
 * module whose text is more than one line ([SdFileList] is the first). The header is not a field
 * because it carries nothing: it is written back from its own literal, so a report that lost its
 * header to a reconnect re-encodes identically to one that had it.
 *
 * ```
 * EndstopReport(listOf(EndstopStateRs("x_min", false))).encode()
 * // Reporting endstop status
 * // x_min: open
 * ```
 *
 * @property states one entry per state line, in firmware order; empty when the header arrived alone
 */
data class EndstopReport(val states: List<EndstopStateRs>) : EndstopRs<EndstopReport> {

    override fun encode(): String {
        val out = StringBuilder(EndstopReportHeader.encode())
        for (state in states) out.append('\n').append(state.encode())
        return out.toString()
    }
}

/**
 * Folds an `M119` answer's lines into one [EndstopReport], one decoded reply at a time.
 *
 * **The same job [SdFileListReader] does for `M20`, with one structural difference: `M119` has no
 * terminator line.** There is no `End file list` equivalent, so the reader cannot tell on its own
 * when the answer is over. What ends a report is instead **the next reply that is not a member of
 * this family** - which is exactly what [offer] answering false means, and which the caller in
 * `:backend:terminal` turns into a [flush].
 *
 * ```
 * val reader = EndstopReportReader()
 * reader.offer(EndstopReportHeader)                   // true  - opened
 * reader.offer(EndstopStateRs("x_min", false))        // true  - folded
 * reader.offer(SimpleOkRs)                            // false - not a member
 * reader.flush()                                      // EndstopReport([EndstopStateRs("x_min", false)])
 * ```
 *
 * **Why "members of this family" and not "every line until `ok`".** Measured with
 * `MultilineInterleaveProbe` against MarlinSim bugfix-2.1.x on localhost:8099, 2026-11-19, with
 * `M155 S1` auto-reporting every second across the read: the header, three state lines and the `ok`
 * all arrived at the same millisecond, and the temperature ticks landed before the burst and after
 * it, never inside. So nothing is spliced into an `M119` answer, and the narrow rule is sound here.
 * The general rule is not: elsewhere on this wire busy notices and `Done printing file` do land
 * between a command and its `ok`, which is why accumulating until `ok` unconditionally was rejected.
 *
 * **A connection that dies mid-report drops it**, because nothing forces a flush on completion.
 * That is the same judgement [SdFileListReader] records - a report without its end is wrong rather
 * than merely partial - and not a regression of it.
 *
 * **One reader per connection, and every call on the owner's one thread.** Plain fields, no atomics,
 * for the reason `GSendWindow` states: the file travels to a port and the caller does not.
 */
class EndstopReportReader {

    private var open = false

    private val states = ArrayList<EndstopStateRs>()

    /**
     * Offers one decoded reply, answering whether it belonged to an `M119` report and was folded
     * into the one being built: the header opens a report, a state line extends it, and nothing
     * else matches.
     *
     * A false answer is the signal to [flush] - see the class doc for why that is the terminator.
     *
     * @param reply whatever the decoder produced for the line, of any type
     * @return true when [reply] is an [EndstopRs] and is now part of the open report
     */
    fun offer(reply: Any?): Boolean {
        if (reply !is EndstopRs<*>) return false
        open = true
        if (reply is EndstopStateRs) states.add(reply)
        return true
    }

    /**
     * Ends whatever report is open and answers it, leaving the reader ready for the next one.
     *
     * ```
     * reader.flush()   // EndstopReport([...]) the first time, null every time after
     * ```
     *
     * @return the assembled report, or null when no report was open
     */
    fun flush(): EndstopReport? {
        if (!open) return null
        val report = EndstopReport(ArrayList(states))
        open = false
        states.clear()
        return report
    }
}
