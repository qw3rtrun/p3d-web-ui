package org.qw3rtrun.p3d.core.msg;

import org.qw3rtrun.p3d.core.msg.GEvent;

public interface CapabilityReportEvent extends GEvent {

    String fullReportString();

    String capability();

    boolean enabled();

}
