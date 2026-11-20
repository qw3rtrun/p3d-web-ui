package org.qw3rtrun.p3d.g.marlin.event

import java.util.UUID
import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.digitsEnd
import org.qw3rtrun.p3d.g.protocol.sameAscii
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * `M115`'s first line - what the firmware is.
 *
 * ```
 * FIRMWARE_NAME:Marlin 2.1.2 SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0 MACHINE_TYPE:3D Printer EXTRUDER_COUNT:1 UUID:cede2a2f-41a2-4748-9b12-c55c62f367ff
 * ```
 *
 * Keys and values are not separated by anything: a value runs until the next `KEY:`, and values
 * contain spaces (`MACHINE_TYPE:3D Printer`). So the fields are found by looking for the *next*
 * key rather than by splitting, which is why this keeps the raw map instead of named properties.
 *
 * The line is identified by its first key, `FIRMWARE_NAME`, which Marlin always writes first.
 * Without that anchor any line holding an uppercase word and a colon would be claimed.
 *
 * ```
 * val info = FirmwareInfoRs.decode("FIRMWARE_NAME:Marlin 2.1 MACHINE_TYPE:3D Printer")
 * info?.machineType()   // "3D Printer"
 * FirmwareInfoRs.decode("MACHINE_TYPE:3D Printer")   // null - no FIRMWARE_NAME anchor
 * ```
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
data class FirmwareInfoRs(val keyValues: Map<String, String>) : GEventRs<FirmwareInfoRs> {

    override fun encode(): String =
        keyValues.entries.joinToString(" ") { (key, value) -> "$key:$value" }

    fun fullReportString(): String = encode()

    /**
     * The machine's UUID, or null when it sends none or sends one that is not a UUID. Spec 9: a
     * malformed field off the wire reads as absent rather than throwing.
     *
     * ```
     * FirmwareInfoRs.decode("FIRMWARE_NAME:M UUID:nope")?.uuid()   // null
     * ```
     */
    fun uuid(): UUID? = keyValues["UUID"]?.let {
        try {
            UUID.fromString(it)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun firmwareName(): String? = keyValues["FIRMWARE_NAME"]

    fun srcCodeUrl(): String? = keyValues["SOURCE_CODE_URL"]

    fun protocolVersion(): String? = keyValues["PROTOCOL_VERSION"]

    fun machineType(): String? = keyValues["MACHINE_TYPE"]

    /**
     * The extruder count, or 0 when it is absent or unreadable.
     *
     * ```
     * FirmwareInfoRs.decode("FIRMWARE_NAME:M EXTRUDER_COUNT:2")?.extruderCount()   // 2
     * ```
     */
    fun extruderCount(): Int = keyValues["EXTRUDER_COUNT"]?.asciiTrim()?.toIntOrNull() ?: 0

    companion object : GRsDecoder<FirmwareInfoRs> {

        private const val REQUIRED_KEY = "FIRMWARE_NAME"

        /**
         * Every `KEY:value` on the line, or null when it does not open with the anchor.
         *
         * **A value ends where the next key begins**, which is the whole shape of this line:
         * nothing separates a value from the next field, and values contain blanks
         * (`MACHINE_TYPE:3D Printer`), so there is nothing to split on. The walk finds the next
         * key and takes everything in front of it.
         *
         * **Keys are uppercase**, and that is load-bearing rather than incidental: it is what keeps
         * a lowercase word inside a value from being read as the start of a field. The anchor is
         * compared case-sensitively for the same reason - `firmware_name:` is not this line.
         */
        override fun decode(line: String): FirmwareInfoRs? {
            val text = line.asciiTrim()
            if (!text.startsWith("$REQUIRED_KEY:")) return null

            val values = LinkedHashMap<String, String>()
            var keyFrom = 0
            while (true) {
                var keyEnd = keyFrom
                while (keyEnd < text.length && isKeyChar(text[keyEnd])) keyEnd++

                val valueFrom = keyEnd + 1
                val next = nextKey(text, valueFrom)
                val valueTo = if (next < 0) text.length else next

                values[text.substring(keyFrom, keyEnd)] =
                    text.substring(valueFrom, valueTo).asciiTrim()

                if (next < 0) return FirmwareInfoRs(values)
                keyFrom = next
            }
        }

        /**
         * Where the next `KEY:` starts at or after [from], or -1 when the rest of the line holds
         * none.
         *
         * A run of key characters that is **not** followed by a colon is skipped whole rather than
         * retried one character in: key characters are maximal, so every shorter run starting
         * inside it ends at the same place and fails the same test. That is the one place this
         * differs in shape from the `find()` it replaces, and it reaches the same answer.
         */
        private fun nextKey(text: String, from: Int): Int {
            var i = from
            while (i < text.length) {
                if (!isKeyStart(text[i])) {
                    i++
                    continue
                }
                var j = i + 1
                while (j < text.length && isKeyChar(text[j])) j++
                if (j < text.length && text[j] == ':') return i
                i = j
            }
            return -1
        }

        /** `A`-`Z`: explicit, because `isLetter()` accepts the whole Unicode category. */
        private fun isKeyStart(c: Char) = c >= 'A' && c <= 'Z'

        private fun isKeyChar(c: Char) =
            isKeyStart(c) || (c >= '0' && c <= '9') || c == '_'
    }
}

/**
 * `M115`'s follow-up lines - one per compile-time feature.
 *
 * ```
 * Cap:EEPROM:1
 * Cap:AUTOREPORT_TEMP:1
 * ```
 *
 * Marlin documents 43 of these in `gcode/host/M115.cpp`, and the name is **not** checked against
 * that list: a capability this code has not heard of is a firmware newer than this code, which is
 * the normal direction of travel and not a broken line.
 *
 * Marlin writes the flag as 0 or 1; the RepRap ecosystem has sent `true`/`false`, and the decoder
 * this replaces accepted both, so both stay readable.
 *
 * ```
 * CapabilityRs.decode("Cap:EEPROM:1")    // CapabilityRs("EEPROM", enabled = true)
 * CapabilityRs.decode("Cap:X:true")      // CapabilityRs("X", enabled = true)
 * ```
 */
data class CapabilityRs(val capability: String, val enabled: Boolean) : GRs<CapabilityRs> {

    override fun encode(): String = "Cap:$capability:" + if (enabled) "1" else "0"

    companion object : GRsDecoder<CapabilityRs> {

        private const val PREFIX = "Cap:"

        /**
         * `Cap:<name>:<flag>`, or null when the line is not one.
         *
         * Three fields and two colons, walked in order. Blanks are allowed **after** each colon and
         * nowhere else - some builds write `Cap: SDCARD:1`, and a blank before a colon would mean
         * the name ran into something else.
         *
         * The name keeps the case the firmware wrote - it is data, and `Cap:eeprom:1` reports a
         * capability called `eeprom`. Only the prefix and the `true`/`false` words fold (spec 1.1:
         * the wire is 7-bit, so a Unicode fold would be deciding something about bytes a printer
         * cannot send).
         */
        override fun decode(line: String): CapabilityRs? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(PREFIX)) return null

            val nameFrom = text.blanksEnd(PREFIX.length)
            var i = nameFrom
            if (i >= text.length || !isNameStart(text[i])) return null
            i++
            while (i < text.length && isNameChar(text[i])) i++
            val nameEnd = i

            if (i >= text.length || text[i] != ':') return null
            val valueFrom = text.blanksEnd(i + 1)
            if (valueFrom >= text.length) return null

            val enabled = readEnabled(text.substring(valueFrom)) ?: return null
            return CapabilityRs(text.substring(nameFrom, nameEnd), enabled)
        }

        /** spec 4.3's letters, which is also what Marlin's own capability names are. */
        private fun isNameStart(c: Char) =
            (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')

        private fun isNameChar(c: Char) =
            isNameStart(c) || (c >= '0' && c <= '9') || c == '_'

        /**
         * The flag, or null when what follows the second colon is not one. Anything but `0`/`1` or
         * `true`/`false` - `yes`, `1.5`, a number with something after it - is declined rather than
         * guessed at. A digit run too wide for an `Int` reads as disabled (spec 9: malformed input
         * off the wire is a value, not a crash).
         */
        private fun readEnabled(value: String): Boolean? {
            if (value.sameAscii("true")) return true
            if (value.sameAscii("false")) return false
            if (value.digitsEnd(0) != value.length) return null
            return (value.toIntOrNull() ?: 0) > 0
        }
    }
}

/**
 * A whole `M115` answer's capabilities, assembled: every [CapabilityRs] the firmware listed, in the
 * order it listed them.
 *
 * **One event per `M115` answer instead of 38**, which is the whole point. A capability on its own
 * is a true fact but not a useful one - a host asks `M115` to learn what the printer can do, and
 * that is the set, not its members arriving one at a time.
 *
 * **`FIRMWARE_NAME:...` is deliberately not folded in.** That line is [FirmwareInfoRs], and it is
 * already one event per answer: it is a single line, so there is no multiplicity for a reader to fix,
 * and it carries its own "announced only when the firmware's fields changed" rule that has nothing
 * to do with this fold. Folding it in would bundle two facts with different lifetimes into one event
 * and force every consumer of either to re-split them.
 *
 * **Scoping the fold to "members of this family" rather than "everything until `ok`" is measured,
 * not assumed.** `CapabilitiesInterleaveProbe` ran `M115` against MarlinSim (bugfix-2.1.x) on
 * 2026-11-20 with `M155 S1` auto-report running: the header, all 38 `Cap:` lines and `ok P15 B3`
 * arrived at the same millisecond offset (31ms after the command was written), with the temperature
 * ticks strictly outside the burst on both sides (a leftover at 0ms, the next at 651ms). Nothing
 * lands inside an `M115` answer, so a reader that ends a report on the first reply that is not a
 * capability cannot be ended early by an unrelated line.
 *
 * **[encode] writes the whole block**, one `Cap:` line each - like [SdFileList], a value that *is* a
 * block, so encoding it as its first line would be a lie. Each line still round-trips through
 * [CapabilityRs.decode] on its own, which `CapabilitiesReportReaderTest` asserts.
 *
 * ```
 * CapabilitiesReport(listOf(CapabilityRs("EEPROM", true), CapabilityRs("SDCARD", true))).encode()
 * // Cap:EEPROM:1
 * // Cap:SDCARD:1
 * ```
 *
 * @see <a href="https://reprap.org/wiki/G-code#M115:_Get_Firmware_Version_and_Capabilities">RepRap G-code, M115</a>
 */
data class CapabilitiesReport(val capabilities: List<CapabilityRs>) : GEventRs<CapabilitiesReport> {

    override fun encode(): String {
        val out = StringBuilder()
        for (capability in capabilities) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(capability.encode())
        }
        return out.toString()
    }
}

/**
 * Folds one `M115` answer's `Cap:` lines into one [CapabilitiesReport], one decoded reply at a time.
 *
 * Same job as [SdFileListReader] does for `M20`: the memory a multi-line answer needs lives in an
 * object the caller owns, because every `GRsDecoder` is a pure line-to-value function and
 * `MarlinRsDecoder` is a singleton registry that two printers share. It takes decoded replies rather
 * than raw lines, so it sits above `decode()` - which is also why neither [CapabilityRs] nor
 * [FirmwareInfoRs] changes to make this work, and why neither leaves the decoder registry.
 *
 * **There is no terminator line.** `M119` opens on a header and `M20` closes on `End file list`;
 * `M115` has neither, so the first `Cap:` line opens the report - it already carries data - and what
 * *ends* it is the next reply that is not a capability. [offer] answering false is that signal, and
 * the caller flushes on it:
 *
 * ```
 * val reader = CapabilitiesReportReader()
 * for (reply in replies) {
 *     if (reader.offer(reply)) continue
 *     reader.flush()?.let { publish(it) }
 *     publish(reply)
 * }
 * ```
 *
 * **A connection that dies mid-burst drops the report.** Nothing flushes, so the capabilities
 * collected so far are discarded with the reader - the same judgement [SdFileListReader] takes, for
 * the same reason: a partial answer reported as a whole one is worse than no answer, because a host
 * cannot tell a firmware that lacks `SDCARD` from one whose answer was cut off before it.
 *
 * **One reader per connection, and every call on the owner's one thread.** It holds a plain `Boolean`
 * and a plain `ArrayList` and no atomics - the contract `GSendWindow` states, and stated here rather
 * than at the call site because this file travels to a port and the caller does not.
 */
class CapabilitiesReportReader {

    private var open = false

    private val capabilities = ArrayList<CapabilityRs>()

    /**
     * Offers one decoded reply to the reader, taking it into the open report when it is a
     * [CapabilityRs] and opening one if none was open.
     *
     * Declining leaves the open report exactly as it was, so a caller may offer every reply it sees
     * and treat a false as "this reply is not mine, and whatever I was collecting is now complete".
     *
     * ```
     * reader.offer(CapabilityRs("EEPROM", true))   // true - a report is open and holds one
     * reader.offer(SimpleOkRs)                     // false - flush, then handle the ok
     * ```
     *
     * @param reply one decoded reply, or null
     * @return true when [reply] was a capability and was taken, false for anything else
     */
    fun offer(reply: Any?): Boolean {
        if (reply !is CapabilityRs) return false
        open = true
        capabilities.add(reply)
        return true
    }

    /**
     * Closes the open report and answers it, or null when no capability has been offered since the
     * last flush - so flushing on every non-capability reply is safe and costs nothing.
     *
     * The report gets its own copy of the list, and the reader is empty afterwards: the next `M115`
     * answer starts clean, and a second flush answers null.
     *
     * ```
     * reader.offer(CapabilityRs("EEPROM", true))
     * reader.flush()   // CapabilitiesReport([CapabilityRs("EEPROM", true)])
     * reader.flush()   // null
     * ```
     *
     * @return the assembled report, or null when nothing was collected
     */
    fun flush(): CapabilitiesReport? {
        if (!open) return null
        val report = CapabilitiesReport(ArrayList(capabilities))
        open = false
        capabilities.clear()
        return report
    }
}
