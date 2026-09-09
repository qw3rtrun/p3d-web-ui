package org.qw3rtrun.p3d.g.marlin.decoder

import org.qw3rtrun.p3d.core.msg.TemperatureReport
import org.qw3rtrun.p3d.core.msg.TemperatureReportedEvent
import org.qw3rtrun.p3d.g.marlin.event.OkTemperatureReported
import org.qw3rtrun.p3d.g.marlin.event.TemperatureReported
import java.util.Optional
import java.util.regex.Matcher
import java.util.regex.Pattern

class TemperatureReportedDecoder : GEventDecoder<TemperatureReportedEvent> {

    override fun decode(line: String): Optional<TemperatureReportedEvent> {
        val matcher = TEMP_REPORT_PATTERN.matcher(line)
        val temps = mutableMapOf<String, Double>()
        val targets = mutableMapOf<String, Double>()
        val pows = mutableMapOf<String, Int>()
        while (matcher.find()) {
            processGroup(matcher, temps, targets, pows)
        }
        val temp = temps["T"] ?: temps["t"]
        val power = pows["@"] ?: pows["t"]
        val target = targets["T"] ?: targets["t"]
        val bedTemp = temps["B"] ?: temps["b"]
        val bedPower = pows["B@"] ?: pows["b"]
        val bedTarget = targets["B"] ?: targets["b"]
        if (temp == null || bedTemp == null || target == null || bedTarget == null || power == null || bedPower == null) {
            return Optional.empty()
        }
        val treport = TemperatureReport(temp, target, power)
        val breport = TemperatureReport(bedTemp, bedTarget, bedPower)
        if (line.length >= 2 && "ok".equals(line.substring(0, 2), ignoreCase = true)) {
            return Optional.of(OkTemperatureReported(treport, breport))
        }
        return Optional.of(TemperatureReported(treport, breport))
    }

    fun processGroup(
        matcher: Matcher,
        temps: MutableMap<String, Double>,
        targets: MutableMap<String, Double>,
        powers: MutableMap<String, Int>
    ) {
        if (matcher.group(3) != null) {
            processTempReport(matcher, temps, targets)
        } else {
            processPower(matcher, powers)
        }
    }

    private fun processPower(matcher: Matcher, powers: MutableMap<String, Int>) {
        // spec 9: a digit run too wide for Int is malformed input off the wire, not a crash. Drop
        // the field; the completeness check in decode() then turns the whole line into absence.
        // The group is [0-9]+, so toIntOrNull() never sees a non-ASCII digit here.
        val power = matcher.group(5).toIntOrNull() ?: return
        powers[matcher.group(4)] = power
    }

    private fun processTempReport(
        matcher: Matcher,
        temps: MutableMap<String, Double>,
        targets: MutableMap<String, Double>
    ) {
        // spec 9: same rule as processPower - an unparsable value drops its field rather than
        // throwing. Both halves are stored or neither, so a half-read field cannot be decoded.
        val temp = matcher.group(2).toDoubleOrNull() ?: return
        val target = matcher.group(3).toDoubleOrNull() ?: return
        temps[matcher.group(1)] = temp
        targets[matcher.group(1)] = target
    }

    companion object {
        private val TEMP_REPORT_PATTERN = Pattern.compile(
            // The dot is escaped: an unescaped `.` in `[0-9]*.` matched any character, so
            // `B:x1` parsed as a number and reached toDouble(). spec 3.1 shape: digits, an
            // optional `.` and more digits.
            "(?>([TtBb]\\d?): *[+-]?((?>[0-9]*\\.)?[0-9]+) */ *((?>[0-9]*\\.)?[0-9]+) *)|(?>([TtBb]?@\\d?): *[+-]?([0-9]+) *)"
        )
    }
}
