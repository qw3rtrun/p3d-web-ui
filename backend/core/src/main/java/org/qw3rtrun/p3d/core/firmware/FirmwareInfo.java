package org.qw3rtrun.p3d.core.firmware;

import lombok.Getter;
import org.qw3rtrun.p3d.api.msg.FirmwareInfoReportEvent;

@Getter
public class FirmwareInfo {

    private FirmwareInfoReportEvent raw;

    public boolean on(FirmwareInfoReportEvent event) {
        raw = event;
        return true;
    }
}
