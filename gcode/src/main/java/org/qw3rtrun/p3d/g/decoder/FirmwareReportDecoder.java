package org.qw3rtrun.p3d.g.decoder;

import org.apache.commons.lang3.StringUtils;
import org.qw3rtrun.p3d.g.event.FirmwareInfoReportEvent;
import org.qw3rtrun.p3d.g.event.FirmwareReport;

import java.util.HashMap;
import java.util.Optional;
import java.util.regex.Pattern;

public class FirmwareReportDecoder implements GEventDecoder<FirmwareInfoReportEvent> {

    private static String FW_PREFIX = "FIRMWARE_NAME";

    private static String SEPARATOR = ":";

    private static Pattern PATTERN = Pattern.compile("((?<field>[A-Z_]+)" + SEPARATOR + ")+");

    @Override
    public Optional<FirmwareInfoReportEvent> decode(String line) {
        if (!StringUtils.startsWithIgnoreCase(line, FW_PREFIX + SEPARATOR)) {
            return Optional.empty();
        }
        var matcher = PATTERN.matcher(line);
        var map = new HashMap<String, String>();
        if (!matcher.find()) {
            return Optional.empty();
        }
        String key = matcher.group();
        int keyEnd = matcher.end();
        while (matcher.find()) {
            map.put(key.substring(0, key.length() - 1), line.substring(keyEnd, matcher.start()).trim());
            key = matcher.group();
            keyEnd = matcher.end();
        }
        map.put(key.substring(0, key.length() - 1), line.substring(keyEnd, line.length()));
        return Optional.of(new FirmwareReport(map));
    }

}
