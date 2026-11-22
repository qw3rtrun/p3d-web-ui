package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.AbortSDPrint;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Abort the print running from the card - {@code M524}, and nothing else.
 *
 * <p><strong>No cooldown, no retract and no park.</strong> Those are what a slicer's end-of-print
 * script does and what an operator may well expect from a Stop button, and adding them here would be
 * a claim about {@code M524} nobody has checked: what the firmware itself does to the heaters and the
 * fan on an abort is unmeasured against this farm's simulator, so a {@code M104 S0} tacked on behind
 * this would either duplicate the firmware's own work or paper over its absence, and there is no way
 * to tell which from here. One command, whose effect is observable as {@code SdNotPrinting} on the
 * next {@code M27}; the heaters are the temperature controls' business, which the monitor page
 * already offers beside this.
 */
public record StopSdPrint() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new AbortSDPrint());
    }
}
