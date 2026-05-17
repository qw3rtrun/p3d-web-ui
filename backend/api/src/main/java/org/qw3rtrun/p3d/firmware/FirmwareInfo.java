package org.qw3rtrun.p3d.firmware;

import lombok.Getter;
import org.qw3rtrun.p3d.core.msg.FirmwareInfoReportEvent;

@Getter
public class FirmwareInfo {

    private FirmwareInfoReportEvent raw;

    public boolean on(FirmwareInfoReportEvent event) {
        raw = event;
        return true;
    }
}
