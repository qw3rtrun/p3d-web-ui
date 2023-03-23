package org.qw3rtrun.p3d.g.decoder;

import org.qw3rtrun.p3d.g.event.OkTemperatureReported;
import org.qw3rtrun.p3d.g.event.TemperatureReport;
import org.qw3rtrun.p3d.g.event.TemperatureReported;
import org.qw3rtrun.p3d.g.event.TemperatureReportedEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemperatureReportedDecoder implements GEventDecoder<TemperatureReportedEvent> {

    private static final Pattern TEMP_REPORT_PATTERN = Pattern.compile(
            "(?>([TtBb]\\d?): *[+-]?((?>[0-9]*.)?[0-9]+) */ *((?>[0-9]*.)?[0-9]+) *)|(?>([TtBb]?@\\d?): *[+-]?([0-9]+) *)"
    );

    @Override
    public Optional<TemperatureReportedEvent> decode(String line) {
        Matcher matcher = TEMP_REPORT_PATTERN.matcher(line);
        Map<String, Double> temps = new HashMap<>();
        Map<String, Double> targets = new HashMap<>();
        Map<String, Integer> pows = new HashMap<>();
        while (matcher.find()) {
            processGroup(matcher, temps, targets, pows);
        }
        var temp = temps.getOrDefault("T", temps.get("t"));
        var power = pows.getOrDefault("@", pows.get("t"));
        var target = targets.getOrDefault("T", targets.get("t"));
        var bedTemp = temps.getOrDefault("B", temps.get("b"));
        var bedPower = pows.getOrDefault("B@", pows.get("b"));
        var bedTarget = targets.getOrDefault("B", targets.get("b"));
        if (temp == null || bedTemp == null || target == null || bedTarget == null || power == null || bedPower == null) {
            return Optional.empty();
        }
        var treport = new TemperatureReport(temp, target, power);
        var breport = new TemperatureReport(bedTemp, bedTarget, bedPower);
        if ("ok".equalsIgnoreCase(line.substring(0, 2))) {
            return Optional.of(new OkTemperatureReported(treport, breport));
        }
        return Optional.of(new TemperatureReported(treport, breport));
    }

    public void processGroup(Matcher matcher, Map<String, Double> temps, Map<String, Double> targets, Map<String, Integer> powers) {
        matcher.group();
        if (matcher.group(3) != null) {
            processTempReport(matcher, temps, targets);
        } else {
            processPower(matcher, powers);
        }
    }

    private void processPower(Matcher matcher, Map<String, Integer> powers) {
        powers.put(matcher.group(4), Integer.valueOf(matcher.group(5)));
    }

    private void processTempReport(Matcher matcher, Map<String, Double> temps, Map<String, Double> targets) {
        temps.put(matcher.group(1), Double.valueOf(matcher.group(2)));
        targets.put(matcher.group(1), Double.valueOf(matcher.group(3)));
    }
}
