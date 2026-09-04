package org.qw3rtrun.p3d.g.decoder

import org.apache.commons.lang3.StringUtils
import org.qw3rtrun.p3d.core.msg.CapabilityReport
import org.qw3rtrun.p3d.core.msg.CapabilityReportEvent
import java.util.Optional

class CapabilityReportDecoder : GEventDecoder<CapabilityReportEvent> {

    override fun decode(line: String): Optional<CapabilityReportEvent> {
        if (line.length < 7 || !line.startsWith(CAP_PREFIX, ignoreCase = true)) {
            return Optional.empty()
        }
        val parts = line.split(SEPARATOR)
        if (parts.size != 3) {
            return Optional.empty()
        }

        val capability = parts[1]
        val enabled = parts[2]
        return if (StringUtils.isNotBlank(capability)) {
            Optional.of(CapabilityReport(line, capability, parseEnabled(enabled)))
        } else {
            Optional.empty()
        }
    }

    internal fun parseEnabled(enabled: String): Boolean {
        if (StringUtils.isNumeric(enabled)) {
            return enabled.toInt() > 0
        }
        return "true".equals(enabled, ignoreCase = true)
    }

    companion object {
        private const val CAP_PREFIX = "Cap"
        private const val SEPARATOR = ":"
    }
}
