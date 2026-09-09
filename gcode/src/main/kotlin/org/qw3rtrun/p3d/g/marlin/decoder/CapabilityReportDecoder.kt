package org.qw3rtrun.p3d.g.marlin.decoder

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
        // spec 9: a value that will not fit an Int is malformed input off the wire, so the line
        // decodes to absence instead of throwing. The raw text still reaches the caller through
        // the unknown-line decoder.
        val enabled = parseEnabled(parts[2]) ?: return Optional.empty()
        return if (StringUtils.isNotBlank(capability)) {
            Optional.of(CapabilityReport(line, capability, enabled))
        } else {
            Optional.empty()
        }
    }

    // null means the value could not be read at all; false means read and off.
    internal fun parseEnabled(enabled: String): Boolean? {
        if (StringUtils.isNumeric(enabled)) {
            return (enabled.toIntOrNull() ?: return null) > 0
        }
        return "true".equals(enabled, ignoreCase = true)
    }

    companion object {
        private const val CAP_PREFIX = "Cap"
        private const val SEPARATOR = ":"
    }
}
