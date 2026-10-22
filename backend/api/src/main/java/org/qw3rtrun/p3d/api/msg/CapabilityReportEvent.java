package org.qw3rtrun.p3d.api.msg;

public interface CapabilityReportEvent extends GEvent {

    String fullReportString();

    String capability();

    boolean enabled();

}
