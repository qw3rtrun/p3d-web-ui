package org.qw3rtrun.p3d.g.marlin.decoder

import org.qw3rtrun.p3d.core.msg.OKReceivedEvent
import org.qw3rtrun.p3d.g.marlin.event.AdvancedOKReceived
import org.qw3rtrun.p3d.g.marlin.event.OKReceived
import java.util.Optional
import java.util.regex.Pattern

class OkDecoder : GEventDecoder<OKReceivedEvent> {

    override fun decode(line: String): Optional<OKReceivedEvent> {
        val okLine = line.trim()
        if ("OK".equals(okLine, ignoreCase = true)) {
            return Optional.of(OKReceived())
        }
        if (okLine.length >= 2 && "OK".equals(okLine.substring(0, 2), ignoreCase = true)) {
            return parseAdvanced(line)
        }
        return Optional.empty()
    }

    private fun parseAdvanced(line: String): Optional<OKReceivedEvent> {
        val matcher = ADVANCED_OK_PATTERN.matcher(line)
        if (!matcher.find()) {
            return Optional.empty()
        }
        var planner = -1
        var blockQueue = -1
        var lineNumber = -1
        // spec 9: the digit groups are unbounded, so a run wider than Int is ordinary wire
        // garbage. Yield absence rather than throwing, and never report a truncated value as a
        // real queue depth or line number. The groups are [0-9]+, so toIntOrNull() cannot see a
        // non-ASCII digit here.
        val first = matcher.group(2).toIntOrNull() ?: return Optional.empty()
        val second = matcher.group(4).toIntOrNull() ?: return Optional.empty()
        when (matcher.group(1)) {
            "P", "p" -> planner = first
            "B", "b" -> blockQueue = first
        }
        when (matcher.group(3)) {
            "B", "b" -> blockQueue = second
            "P", "p" -> planner = second
        }
        // group(5) is null when the optional N field is absent; equals() handles that.
        if ("N".equals(matcher.group(5), ignoreCase = true)) {
            lineNumber = matcher.group(6).toIntOrNull() ?: return Optional.empty()
        }

        return Optional.of(AdvancedOKReceived(planner, blockQueue, lineNumber))
    }

    companion object {
        private val ADVANCED_OK_PATTERN = Pattern.compile(
            "^[oO][kK] +([PpBb])([0-9]+) +([PpBb])([0-9]+)(?> +([Nn])([0-9]+))?"
        )
    }
}
