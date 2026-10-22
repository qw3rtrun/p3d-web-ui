package org.qw3rtrun.p3d.api.msg;

/**
 * A temperature reading, as the application models it: one hotend and one bed.
 *
 * <p>The application's view and not the wire's. A Marlin reply can carry a chamber, a probe, a
 * cooler, a board sensor, a redundant sensor and any number of hotends; {@code :gcode} models all of
 * them, because that is what the firmware sends. This record is what the host currently has
 * anything to do with, and it grows when a consumer appears for one of the others - not before, and
 * not because the wire format happens to carry it.
 *
 * <p>The conversion lives in {@code :backend:terminal}, which is the only place that sees both
 * models.
 */
public record TemperatureReported(TemperatureReport hotend, TemperatureReport bed)
        implements TemperatureReportedEvent {
}
