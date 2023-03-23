package org.qw3rtrun.p3d.g.event;

public record OkTemperatureReported(TemperatureReport hotend,
                                    TemperatureReport bed) implements OKReceivedEvent, TemperatureReportedEvent {
}
