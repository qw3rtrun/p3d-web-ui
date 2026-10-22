package org.qw3rtrun.p3d.api.msg;

import java.util.UUID;

public interface FirmwareInfoReportEvent extends GEvent {

    /**
     * Every key the firmware reported, in its own words.
     *
     * <p>The named accessors below are conveniences over this; the map is the actual report. What
     * {@code M115} sends differs between builds, so a consumer that wants a key no accessor names -
     * and the roster, which persists the lot - reads it here.
     */
    java.util.Map<String, String> keyValues();

    String fullReportString();

    UUID uuid();

    String firmwareName();

    String srcCodeUrl();

    String protocolVersion();

    String machineType();

    int extruderCount();

}
