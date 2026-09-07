package org.qw3rtrun.p3d.g.marlin.decoder

import org.qw3rtrun.p3d.core.msg.FirmwareInfoReportEvent
import org.qw3rtrun.p3d.core.msg.FirmwareReport
import java.util.Optional
import java.util.regex.Pattern

class FirmwareReportDecoder : GEventDecoder<FirmwareInfoReportEvent> {

    override fun decode(line: String): Optional<FirmwareInfoReportEvent> {
        if (!line.startsWith(FW_PREFIX + SEPARATOR, ignoreCase = true)) {
            return Optional.empty()
        }
        val matcher = PATTERN.matcher(line)
        val map = mutableMapOf<String, String>()
        if (!matcher.find()) {
            return Optional.empty()
        }
        var key = matcher.group()
        var keyEnd = matcher.end()
        while (matcher.find()) {
            map[key.substring(0, key.length - 1)] = line.substring(keyEnd, matcher.start()).trim()
            key = matcher.group()
            keyEnd = matcher.end()
        }
        map[key.substring(0, key.length - 1)] = line.substring(keyEnd, line.length)
        return Optional.of(FirmwareReport(map))
    }

    companion object {
        private const val FW_PREFIX = "FIRMWARE_NAME"
        private const val SEPARATOR = ":"
        private val PATTERN = Pattern.compile("((?<field>[A-Z_]+)$SEPARATOR)+")
    }
}
