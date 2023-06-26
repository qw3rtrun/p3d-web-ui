package org.qw3rtrun.p3d.g.event;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.UUID;

public record FirmwareReport(
        Map<String, String> keyValues
) implements FirmwareInfoReportEvent {
    @Override
    public String fullReportString() {
        return keyValues.toString();
    }

    @Override
    public UUID uuid() {
        return keyValues.containsKey("UUID") ? UUID.fromString(keyValues.get("UUID")) : null;
    }

    @Override
    public String firmwareName() {
        return keyValues.get("FIRMWARE_NAME");
    }

    @Override
    public String srcCodeUrl() {
        return keyValues.get("SOURCE_CODE_URL");
    }

    @Override
    public String protocolVersion() {
        return keyValues.get("PROTOCOL_VERSION");
    }

    @Override
    public String machineType() {
        return keyValues.get("MACHINE_TYPE");
    }

    @Override
    public int extruderCount() {
        var ecStr = keyValues.get("EXTRUDER_COUNT");
        return StringUtils.isNumeric(ecStr) ? Integer.parseInt(ecStr) : 1;
    }
}
