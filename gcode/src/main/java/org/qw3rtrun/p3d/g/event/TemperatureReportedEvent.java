package org.qw3rtrun.p3d.g.event;

public interface TemperatureReportedEvent extends GEvent {
    TemperatureReport hotend();
    TemperatureReport bed();
}
