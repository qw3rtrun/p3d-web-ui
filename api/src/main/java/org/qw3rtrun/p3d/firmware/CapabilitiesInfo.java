package org.qw3rtrun.p3d.firmware;

import lombok.Getter;
import org.qw3rtrun.p3d.core.msg.CapabilityReportEvent;
import org.qw3rtrun.p3d.core.msg.FirmwareInfoReportEvent;

import java.util.HashMap;
import java.util.Map;

@Getter
public class CapabilitiesInfo {

    private Map<String, CapabilityReportEvent> raw = new HashMap<>();

    public boolean on(CapabilityReportEvent event) {
        var previous = raw.put(event.capability(), event);
        return previous == null || !event.fullReportString().equals(previous.fullReportString());
    }


}
