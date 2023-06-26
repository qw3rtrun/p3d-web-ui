package org.qw3rtrun.p3d.g.event;

import java.util.UUID;

public interface CapabilityReportEvent extends GEvent {

    String fullReportString();

    String capability();

    boolean enabled();

}
