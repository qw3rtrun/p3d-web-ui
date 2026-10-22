package org.qw3rtrun.p3d.api.msg;

import java.math.BigDecimal;

/**
 * Heat a hotend to {@code temp}, or let it cool by asking for zero.
 *
 * @param index which hotend; null means the active one
 * @param temp  degrees Celsius. The scale the caller chooses is the scale that reaches the wire
 */
public record SetHotendTarget(Integer index, BigDecimal temp) implements PrinterCommand {
}
