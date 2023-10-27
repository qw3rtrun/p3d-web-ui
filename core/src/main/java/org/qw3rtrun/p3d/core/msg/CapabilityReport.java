package org.qw3rtrun.p3d.core.msg;

import org.qw3rtrun.p3d.core.msg.CapabilityReportEvent;

public record CapabilityReport(String fullReportString, String capability, boolean enabled) implements CapabilityReportEvent {

}
