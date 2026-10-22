package org.qw3rtrun.p3d.api.msg;

import java.math.BigDecimal;

/** Heat the bed to {@code temp}, or let it cool by asking for zero. */
public record SetBedTarget(BigDecimal temp) implements PrinterCommand {
}
