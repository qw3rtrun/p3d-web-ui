package org.qw3rtrun.p3d.g.event;

import java.util.UUID;

public interface FirmwareInfoReportEvent extends GEvent {

    String fullReportString();

    UUID uuid();

    String firmwareName();

    String srcCodeUrl();

    String protocolVersion();

    String machineType();

    int extruderCount();

}
