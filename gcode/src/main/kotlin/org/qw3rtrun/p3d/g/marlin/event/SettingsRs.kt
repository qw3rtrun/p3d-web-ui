package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.digitsEnd
import org.qw3rtrun.p3d.g.protocol.edgeStart
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * `M503`'s settings report and `M504`'s EEPROM notice: what the machine says its own
 * configuration is.
 *
 * **One interface over the family, because a Settings view wants one subscription** - the same
 * reason [SdCardRs] has one. A caller matching `SettingsRs<*>` gets the whole report: the section
 * headers, the setting lines under them, and the EEPROM health notice.
 *
 * Captured off MarlinSim `FIRMWARE_NAME:Marlin bugfix-2.1.x (Oct 29 2026 10:28:56)
 * MACHINE_TYPE:MarlinSim`. `M503`, `M503 S1`, `M503 S` and `M503 C` all produce byte-identical
 * output - a section header, then the commands that would restore that section:
 *
 * ```
 * echo:; Steps per unit:                       SettingsSection("Steps per unit")
 * echo:  M92 X80.00 Y80.00 Z400.00 E500.00     SettingsValue("M92", "M92 X80.00 ...")
 * echo:EEPROM OK                               EepromReport("OK")      - M504
 * ```
 *
 * **`M503 S0` answers in a second shape nobody documents**: no `echo:` prefix and no section
 * headers at all, with the same two-space indent on the value lines. A bare single-setting query
 * (`M92` on its own) answers in exactly that shape too, which is why one decoder covers both and
 * why the two must produce the same value:
 *
 * ```
 * >> M503 S0                                   >> M92
 * <<   M92 X80.00 Y80.00 Z400.00 E500.00       <<   M92 X81.00 Y80.00 Z400.00 E500.00
 * << ok P15 B3                                 << ok P15 B3
 * ```
 *
 * **The two-space indent is the whole guard.** It is what separates a settings line from every
 * other thing a printer says, so the indentation is read off the raw line and never trimmed away
 * first: `echo:M104 Invalid extruder 5` and `echo:Unknown command: "M9999"` carry the prefix but
 * no indent, so they stay [InvalidExtruder] and [UnknownCommand]; an unindented `M92 X80` is not a
 * settings reply at all.
 *
 * Three of `M503`'s neighbours are deliberately not here. `M500` answers
 * `//action:notification Settings Stored`, which is already an `ActionRs`. `M501` and `M502`
 * answer a bare `ok` and say nothing else. And `M504`'s *failure* text is unmeasured - the
 * simulator always reports OK - which is why [EepromReport] carries the message verbatim instead
 * of a boolean it would have to have guessed.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M503.html">Marlin M503</a>
 */
interface SettingsRs<T : SettingsRs<T>> : GEventRs<T>

/**
 * `echo:; <title>:` - a heading in the `M503` dump, naming the group of settings under it.
 *
 * Present only in the prefixed shape: `M503 S0` writes the values with no headings, so a consumer
 * grouping the report has to tolerate their absence.
 *
 * **The title ends at the final colon, not the first.** Marlin writes headings that carry their own
 * punctuation - `Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>):` and
 * `Max feedrates (units/s):` - so reading forward to the first `:` would cut
 * `Filament settings (Disabled)` in the wrong place one day. The line is known to end with the
 * closing colon, so the last one is the one at the end, and a heading that does not end with a
 * colon does not decode rather than being guessed at.
 *
 * ```
 * SettingsSection.decode("echo:; Steps per unit:")   // SettingsSection("Steps per unit")
 * SettingsSection("Steps per unit").encode()         // "echo:; Steps per unit:"
 * ```
 *
 * @property title the heading, without the `; ` that introduces it or the `:` that closes it
 */
data class SettingsSection(val title: String) : SettingsRs<SettingsSection> {

    override fun encode(): String = EchoMessage.PREFIX + OPEN + title + CLOSE

    companion object : GRsDecoder<SettingsSection> {

        private const val OPEN = "; "

        private const val CLOSE = ":"

        /**
         * The heading between `;` and the closing `:`, or null when the line is not one.
         *
         * Scans the raw line rather than a trimmed copy, because the blanks matter to
         * [SettingsValue] and all three decoders in this family have to read the same bytes. Only
         * the trailing edge - spec 2.1's blanks and spec 1.2's terminators - is discounted.
         *
         * ```
         * SettingsSection.decode("echo:; Hotend PID:")   // SettingsSection("Hotend PID")
         * SettingsSection.decode("echo:; Hotend PID")    // null - no closing colon
         * SettingsSection.decode("echo:  M92 X80.00")    // null - a value, not a heading
         * ```
         *
         * @param line one received line, as it was framed
         * @return the heading, or null when [line] is some other kind of reply
         */
        override fun decode(line: String): SettingsSection? {
            if (!line.startsWithAscii(EchoMessage.PREFIX)) return null

            val end = line.edgeStart()
            var at = line.blanksEnd(EchoMessage.PREFIX.length)
            if (at >= end || line[at] != ';') return null

            at = line.blanksEnd(at + 1)
            val last = end - 1
            if (last <= at || line[last] != ':') return null

            return SettingsSection(line.substring(at, last))
        }
    }
}

/**
 * `echo:  <command>` - one line of the `M503` report: the G-code that would restore one setting.
 *
 * **The words are deliberately not parsed.** The report *is* a list of commands, and that is all
 * this type claims: [command] is the text the firmware wrote, and a caller that wants `X80.00` out
 * of `M92 X80.00 Y80.00` runs it through `GTokenizer`, which already reads exactly this syntax. A
 * second parse model here would be a second place for the number rules to drift.
 *
 * [code] is the head - `G` or `M`, its digits, and an optional `.`-subcode - kept in the firmware's
 * own casing, because it is what a consumer switches on to decide which setting a line is about.
 *
 * A trailing comment stays in [command] verbatim: `G21 ; (mm)` and `M149 C ; Units in Celsius` are
 * what the firmware said, and the note is part of the report rather than noise to strip.
 *
 * **The `echo:`-prefixed indented form is the canonical spelling**, in the sense `GRs.encode`
 * means it. A value read from an `M503 S0` line - which arrives with no prefix - re-encodes to the
 * `echo:` form, so the round-trip is a round-trip of the *value* and not of the bytes. That is not
 * a defect: both spellings decode to the same value, and one of them has to be the one `encode`
 * writes.
 *
 * ```
 * SettingsValue.decode("echo:  M92 X80.00 Y80.00")   // SettingsValue("M92", "M92 X80.00 Y80.00")
 * SettingsValue.decode("  M92 X80.00 Y80.00")        // the same value - the M503 S0 shape
 * SettingsValue.decode("M92 X80.00")                 // null - no indent, so not a report line
 * SettingsValue("M92", "M92 X80.00").encode()        // "echo:  M92 X80.00"
 * ```
 *
 * @property code the command head the line reports on, as the firmware spelled it
 * @property command the whole line of G-code, head and any trailing comment included
 */
data class SettingsValue(val code: String, val command: String) : SettingsRs<SettingsValue> {

    override fun encode(): String = EchoMessage.PREFIX + INDENT + command

    companion object : GRsDecoder<SettingsValue> {

        private const val INDENT = "  "

        /**
         * The head and the whole command, or null when the line is not a settings value.
         *
         * One walk down the raw line. `echo:` is optional - that is the only difference between the
         * two shapes `M503` answers in - but the indent behind it is **not**, in either shape, and
         * neither is a head of `G` or `M` followed by digits, an optional `.`-subcode, and then
         * either the end of the line or a blank.
         *
         * Those three conditions together are what keep this decoder off lines that belong to
         * someone else. The ones that would otherwise be claimed, and the condition that rejects
         * each: `echo:M104 Invalid extruder 5` has no indent; `  Free Memory: 2048` and
         * `  TEST.GCO 47` do not start with `G` or `M`; `  G1.GCO 47` has a `.` with no digits
         * behind it, so its head never ends.
         *
         * ```
         * SettingsValue.decode("echo:  G21 ; (mm)")   // SettingsValue("G21", "G21 ; (mm)")
         * SettingsValue.decode("  TEST.GCO 47")       // null
         * SettingsValue.decode("  G1.GCO 47")         // null
         * ```
         *
         * @param line one received line, as it was framed
         * @return the setting line, or null when [line] is some other kind of reply
         */
        override fun decode(line: String): SettingsValue? {
            val body = if (line.startsWithAscii(EchoMessage.PREFIX)) EchoMessage.PREFIX.length else 0

            val end = line.edgeStart()
            val from = line.blanksEnd(body)
            if (from == body || from >= end) return null

            val head = line[from]
            if (head != 'G' && head != 'g' && head != 'M' && head != 'm') return null

            var at = line.digitsEnd(from + 1)
            if (at == from + 1) return null
            if (at < end && line[at] == '.') {
                val fraction = line.digitsEnd(at + 1)
                if (fraction == at + 1) return null
                at = fraction
            }
            if (at < end && line[at] != ' ' && line[at] != '\t') return null

            return SettingsValue(line.substring(from, at), line.substring(from, end))
        }
    }
}

/**
 * `echo:EEPROM <message>` - `M504`'s verdict on the stored settings.
 *
 * **The message is kept verbatim rather than reduced to a boolean**, because only the healthy
 * answer has been measured: MarlinSim writes `echo:EEPROM OK` and has no way to be asked for a
 * corrupt one. A boolean would have required inventing the failure text and a rule for recognising
 * it; a string says exactly what was observed and lets a host show the rest.
 *
 * ```
 * EepromReport.decode("echo:EEPROM OK")   // EepromReport("OK")
 * EepromReport("OK").encode()             // "echo:EEPROM OK"
 * ```
 *
 * @property message what the firmware said about the EEPROM, as it wrote it
 */
data class EepromReport(val message: String) : SettingsRs<EepromReport> {

    override fun encode(): String = EchoMessage.PREFIX + BODY + message

    companion object : GRsDecoder<EepromReport> {

        private const val BODY = "EEPROM "

        /**
         * The verdict behind `echo:EEPROM`, or null when the line is not one.
         *
         * `EEPROM` has to sit immediately behind `echo:` with no indent, and it has to be followed
         * by a blank and a non-empty message. Strict on purpose: an indented spelling is not one
         * the firmware writes, so accepting it would mean re-encoding an unmeasured line into a
         * measured one.
         *
         * ```
         * EepromReport.decode("echo:EEPROM OK")   // EepromReport("OK")
         * EepromReport.decode("echo:EEPROM")      // null - nothing said about it
         * ```
         *
         * @param line one received line, as it was framed
         * @return the verdict, or null when [line] is some other kind of reply
         */
        override fun decode(line: String): EepromReport? {
            if (!line.startsWithAscii(EchoMessage.PREFIX)) return null
            if (!line.startsWithAscii(BODY, EchoMessage.PREFIX.length)) return null

            val at = line.blanksEnd(EchoMessage.PREFIX.length + BODY.length)
            val message = line.substring(at).asciiTrim()
            return if (message.isEmpty()) null else EepromReport(message)
        }
    }
}

/**
 * Any line of a settings report, tried most specific first.
 *
 * [EepromReport] before [SettingsSection] before [SettingsValue]: the three are disjoint by
 * construction - a heading needs a `;`, a value needs a `G`/`M` head, the EEPROM notice needs its
 * own word - so the order is belt and braces rather than load-bearing.
 *
 * Registered in `MarlinRsDecoder` **ahead of** `EchoRsDecoder`, and `EchoMessage` excludes
 * whatever this claims, because `echo:` is otherwise a catch-all that would take every line of the
 * dump as prose. That is the same partition `SdCardOk` and `SdFreshFile` keep, and
 * `MarlinRsTest` pins it.
 *
 * ```
 * SettingsRsDecoder.decode("echo:; Hotend PID:")     // SettingsSection("Hotend PID")
 * SettingsRsDecoder.decode("  M92 X80.00")           // SettingsValue("M92", "M92 X80.00")
 * SettingsRsDecoder.decode("echo:Print time: 0s")    // null - ordinary echo prose
 * ```
 */
object SettingsRsDecoder : GRsDecoder<SettingsRs<*>> {

    private val decoders: List<GRsDecoder<SettingsRs<*>>> = listOf(
        EepromReport,
        SettingsSection,
        SettingsValue,
    )

    override fun decode(line: String): SettingsRs<*>? = decoders.firstNotNullOfOrNull { it.decode(line) }
}
