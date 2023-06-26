package org.qw3rtrun.p3d.g.decoder;

import org.apache.commons.lang3.StringUtils;
import org.qw3rtrun.p3d.g.event.CapabilityReport;
import org.qw3rtrun.p3d.g.event.CapabilityReportEvent;

import java.util.Optional;

public class CapabilityReportDecoder implements GEventDecoder<CapabilityReportEvent> {

    private static String CAP_PREFIX = "Cap";

    private static String SEPARATOR = ":";

    @Override
    public Optional<CapabilityReportEvent> decode(String line) {
        if (line.length() < 7 || !StringUtils.startsWithIgnoreCase(line, CAP_PREFIX)) {
            return Optional.empty();
        }
        var parts = line.split(SEPARATOR);
        if (parts.length != 3) {
            return Optional.empty();
        }

        var capability = parts[1];
        var enabled = parts[2];
        return StringUtils.isNotBlank(capability) ?
                Optional.of(new CapabilityReport(line, capability, parseEnabled(enabled)))
                : Optional.empty();
    }

    boolean parseEnabled(String enabled) {
        if (StringUtils.isNumeric(enabled)) {
            return Integer.parseInt(enabled) > 0;
        }
        return "true".equalsIgnoreCase(enabled);
    }
}
