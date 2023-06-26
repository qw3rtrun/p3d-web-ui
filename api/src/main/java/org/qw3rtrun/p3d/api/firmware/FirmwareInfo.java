package org.qw3rtrun.p3d.api.firmware;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.qw3rtrun.p3d.g.event.CapabilityReportEvent;
import org.qw3rtrun.p3d.g.event.FirmwareInfoReportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
public class FirmwareInfo {

    private FirmwareInfoReportEvent info;

    private Map<String, Boolean> capabilities = new HashMap<>();

    public boolean on(FirmwareInfoReportEvent event) {
        info = event;
        return true;
    }

    public boolean on(CapabilityReportEvent event) {
        var previous = capabilities.put(event.capability(), event.enabled());
        return previous == null || event.enabled() != previous;
    }
}
