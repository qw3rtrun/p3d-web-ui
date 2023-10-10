package org.qw3rtrun.p3d.g.event;

import org.qw3rtrun.p3d.core.msg.GEvent;

public interface TemperatureReportedEvent extends GEvent {
    TemperatureReport hotend();
    TemperatureReport bed();
}
