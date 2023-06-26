package org.qw3rtrun.p3d.g.event;

public record CapabilityReport(String fullReportString, String capability, boolean enabled) implements CapabilityReportEvent {

}
