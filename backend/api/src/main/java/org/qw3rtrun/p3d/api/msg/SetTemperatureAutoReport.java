package org.qw3rtrun.p3d.api.msg;

/**
 * Have the machine report its temperatures every {@code period} seconds, or stop with zero.
 *
 * <p>The field is {@code period} because that is what the browser has always sent. It used to bind
 * to a generated class whose property is {@code seconds}, so it bound to nothing and every
 * auto-report request silently asked for the firmware's default instead of the period the user
 * chose.
 *
 * @param period seconds between reports; 0 stops them, null leaves it to the firmware
 */
public record SetTemperatureAutoReport(Integer period) implements PrinterCommand {
}
