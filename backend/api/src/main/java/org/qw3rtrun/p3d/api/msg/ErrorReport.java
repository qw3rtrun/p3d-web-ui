package org.qw3rtrun.p3d.api.msg;

public record ErrorReport(String message, Integer lastLine) implements ErrorReportEvent {
}
