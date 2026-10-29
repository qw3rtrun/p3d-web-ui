package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * Heat a hotend to {@code temp}, or let it cool by asking for zero - {@code M104}.
 *
 * @param index which hotend; null means the active one, which is tool 0
 * @param temp  degrees Celsius. The scale the caller chooses is the scale that reaches the wire
 */
public record SetHotendTarget(Integer index, BigDecimal temp) implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(MarlinG.INSTANCE.m104(temp, index == null ? 0 : index));
    }
}
