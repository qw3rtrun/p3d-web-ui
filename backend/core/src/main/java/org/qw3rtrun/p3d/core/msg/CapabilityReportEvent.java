package org.qw3rtrun.p3d.core.msg;

public interface CapabilityReportEvent extends GEvent {

    String fullReportString();

    String capability();

    boolean enabled();

}
