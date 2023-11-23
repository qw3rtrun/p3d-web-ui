package org.qw3rtrun.p3d.firmware;

import lombok.Getter;
import org.qw3rtrun.p3d.core.msg.CapabilityReportEvent;
import org.qw3rtrun.p3d.core.msg.FirmwareInfoReportEvent;

import java.util.HashMap;
import java.util.Map;

@Getter
public class FirmwareInfo {

    private FirmwareInfoReportEvent raw;

    public boolean on(FirmwareInfoReportEvent event) {
        raw = event;
        return true;
    }
}
