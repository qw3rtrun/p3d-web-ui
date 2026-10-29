package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/** Heat the bed to {@code temp}, or let it cool by asking for zero - {@code M140}. */
public record SetBedTarget(BigDecimal temp) implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(MarlinG.INSTANCE.m140(temp));
    }
}
