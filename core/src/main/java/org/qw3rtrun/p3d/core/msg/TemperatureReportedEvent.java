package org.qw3rtrun.p3d.core.msg;

public interface TemperatureReportedEvent extends GEvent {
    TemperatureReport hotend();

    TemperatureReport bed();
}
