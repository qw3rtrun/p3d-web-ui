package org.qw3rtrun.p3d.api.msg;

import lombok.NonNull;

public record CapabilityReport(@NonNull String fullReportString, @NonNull String capability,
                               boolean enabled) implements CapabilityReportEvent {

}
