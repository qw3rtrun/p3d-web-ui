package org.qw3rtrun.p3d.api.msg;

/**
 * Ask for one temperature reading now, rather than waiting for the next auto-report.
 *
 * @param index which hotend to report on; null means all of them
 */
public record RequestTemperatureReport(Integer index) implements PrinterCommand {
}
