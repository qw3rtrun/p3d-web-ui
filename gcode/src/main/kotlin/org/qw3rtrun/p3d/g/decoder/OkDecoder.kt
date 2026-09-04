package org.qw3rtrun.p3d.g.decoder

import org.qw3rtrun.p3d.core.msg.OKReceivedEvent
import org.qw3rtrun.p3d.g.event.AdvancedOKReceived
import org.qw3rtrun.p3d.g.event.OKReceived
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
        when (matcher.group(1)) {
            "P", "p" -> planner = matcher.group(2).toInt()
            "B", "b" -> blockQueue = matcher.group(2).toInt()
        }
        when (matcher.group(3)) {
            "B", "b" -> blockQueue = matcher.group(4).toInt()
            "P", "p" -> planner = matcher.group(4).toInt()
        }
        if ("N".equals(matcher.group(5), ignoreCase = true)) {
            lineNumber = matcher.group(6).toInt()
        }

        return Optional.of(AdvancedOKReceived(planner, blockQueue, lineNumber))
    }

    companion object {
        private val ADVANCED_OK_PATTERN = Pattern.compile(
            "^[oO][kK] +([PpBb])([0-9]+) +([PpBb])([0-9]+)(?> +([Nn])([0-9]+))?"
        )
    }
}
