package org.qw3rtrun.p3d.api.msg;

public interface TemperatureReportedEvent extends GEvent {
    TemperatureReport hotend();

    TemperatureReport bed();
}
