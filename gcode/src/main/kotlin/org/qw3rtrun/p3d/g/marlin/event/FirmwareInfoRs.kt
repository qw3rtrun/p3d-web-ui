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
 * @property keyValues every field, keyed by its name, in the order the line wrote them
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
     *
     * @return the parsed `UUID` field, or null
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
     *
     * @return the `EXTRUDER_COUNT` field as an integer, or 0
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
 *
 * @property capability the feature's name, as the firmware wrote it
 * @property enabled whether the feature is compiled in and on
 */
data class CapabilityRs(val capability: String, val enabled: Boolean) : GRs<CapabilityRs> {

    override fun encode(): String = "Cap:$capability:" + if (enabled) "1" else "0"

    companion object : GRsDecoder<CapabilityRs> {

        private const val PREFIX = "Cap:"

        /**
         * `Cap:<name>:<flag>`, or null when the line is not one.
         *
         * Three fields and two colons, walked in order. The blanks are allowed **after** each
         * colon and nowhere else, which is what the pattern this replaced said with two `[ \t]*`:
         * some builds write `Cap: SDCARD:1`, and a blank before a colon would mean the name ran
         * into something else.
         *
         * The name keeps the case the firmware wrote - it is data, and `Cap:eeprom:1` reports a
         * capability called `eeprom`. Only the prefix and the `true`/`false` words fold, through
         * `asciiFold` (spec 1.1: the wire is 7-bit, so a Unicode fold would be deciding something
         * about bytes a printer cannot send).
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
         * The flag, or null when what follows the second colon is not one.
         *
         * Marlin writes `0` or `1`; the RepRap ecosystem has sent `true` and `false`, and the
         * decoder this file replaced accepted both, so both stay readable. Anything else - `yes`,
         * `1.5`, a number with something after it - is a line this decoder declines rather than
         * one it guesses at.
         *
         * **A digit run too wide for an `Int` reads as disabled** (spec 9: malformed input off the
         * wire is a value, not a crash). Zero is the safe reading of a number nobody can
         * interpret, and it is what the regex and `toIntOrNull` did between them before.
         */
        private fun readEnabled(value: String): Boolean? {
            if (value.sameAscii("true")) return true
            if (value.sameAscii("false")) return false
            if (value.digitsEnd(0) != value.length) return null
            return (value.toIntOrNull() ?: 0) > 0
        }
    }
}
