package org.qw3rtrun.p3d.g.event;

public record TemperatureReported(TemperatureReport hotend,
                                  TemperatureReport bed) implements TemperatureReportedEvent {
}
