package org.qw3rtrun.p3d.g.event;

import org.qw3rtrun.p3d.core.msg.OKReceivedEvent;
import org.qw3rtrun.p3d.core.msg.TemperatureReport;
import org.qw3rtrun.p3d.core.msg.TemperatureReportedEvent;

public record OkTemperatureReported(TemperatureReport hotend,
                                    TemperatureReport bed) implements OKReceivedEvent, TemperatureReportedEvent {
}
