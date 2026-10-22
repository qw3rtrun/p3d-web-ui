package org.qw3rtrun.p3d.api.msg;

public record BusyReport(String reason) implements MachineBusyEvent {
}
