package org.qw3rtrun.p3d.g.event;

import org.qw3rtrun.p3d.core.msg.GEvent;

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
