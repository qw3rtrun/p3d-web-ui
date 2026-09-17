package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.core.msg.CapabilityReportEvent
import org.qw3rtrun.p3d.core.msg.FirmwareInfoReportEvent
import org.qw3rtrun.p3d.g.code.dsl.GRsDecoder
import org.qw3rtrun.p3d.g.code.dsl.GRs
import java.util.UUID
import java.util.regex.Pattern

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
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
data class FirmwareInfoRs(val keyValues: Map<String, String>) : GRs<FirmwareInfoRs>, FirmwareInfoReportEvent {

    override fun encode(): String =
        keyValues.entries.joinToString(" ") { (key, value) -> "$key:$value" }

    override fun fullReportString(): String = encode()

    /** The machine's UUID, or null when it sends none or sends one that is not a UUID. */
    override fun uuid(): UUID? = keyValues["UUID"]?.let {
        try {
            UUID.fromString(it)
        } catch (e: IllegalArgumentException) {
            // spec 9: a malformed field off the wire reads as absent rather than throwing.
            null
        }
    }

    override fun firmwareName(): String? = keyValues["FIRMWARE_NAME"]

    override fun srcCodeUrl(): String? = keyValues["SOURCE_CODE_URL"]

    override fun protocolVersion(): String? = keyValues["PROTOCOL_VERSION"]

    override fun machineType(): String? = keyValues["MACHINE_TYPE"]

    /** The extruder count, or 0 when it is absent or unreadable. */
    override fun extruderCount(): Int = keyValues["EXTRUDER_COUNT"]?.trim()?.toIntOrNull() ?: 0

    companion object : GRsDecoder<FirmwareInfoRs> {

        private const val REQUIRED_KEY = "FIRMWARE_NAME"

        private val KEY = Pattern.compile("([A-Z][A-Z_0-9]*):")

        override fun match(line: String): Boolean = scan(line) != null

        override fun decodeParams(line: String): FirmwareInfoRs {
            val scanned = scan(line)
            require(scanned != null) { "not a firmware info report: $line" }
            return scanned
        }

        private fun scan(line: String): FirmwareInfoRs? {
            val text = line.trim()
            // The line is identified by its first key, which Marlin always writes first. Without
            // that anchor any line holding an uppercase word and a colon would be claimed.
            if (!text.startsWith("$REQUIRED_KEY:")) return null
            val matcher = KEY.matcher(text)
            if (!matcher.find()) return null
            val values = LinkedHashMap<String, String>()
            var key = matcher.group(1)
            var valueFrom = matcher.end()
            while (matcher.find()) {
                values[key] = text.substring(valueFrom, matcher.start()).trim()
                key = matcher.group(1)
                valueFrom = matcher.end()
            }
            values[key] = text.substring(valueFrom).trim()
            return FirmwareInfoRs(values)
        }
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
 */
data class CapabilityRs(val capability: String, val enabled: Boolean) : GRs<CapabilityRs>, CapabilityReportEvent {

    override fun encode(): String = "Cap:$capability:" + if (enabled) "1" else "0"

    override fun fullReportString(): String = encode()

    override fun capability(): String = capability

    override fun enabled(): Boolean = enabled

    companion object : GRsDecoder<CapabilityRs> {

        private val PATTERN = Pattern.compile(
            "^Cap:[ \t]*([A-Za-z][A-Za-z_0-9]*):[ \t]*([0-9]+|true|false)$",
            Pattern.CASE_INSENSITIVE
        )

        override fun match(line: String): Boolean = PATTERN.matcher(line.trim()).matches()

        override fun decodeParams(line: String): CapabilityRs {
            val matcher = PATTERN.matcher(line.trim())
            require(matcher.matches()) { "not a capability report: $line" }
            return CapabilityRs(matcher.group(1), readEnabled(matcher.group(2)))
        }

        // Marlin writes 0 or 1; the RepRap ecosystem has sent `true`/`false`, and the decoder this
        // replaces accepted both, so both stay readable.
        private fun readEnabled(value: String): Boolean =
            if (value.equals("true", ignoreCase = true)) true
            else if (value.equals("false", ignoreCase = true)) false
            else (value.toIntOrNull() ?: 0) > 0
    }
}
