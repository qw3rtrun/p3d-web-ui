package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.ReleaseSDCard;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Release the SD card - {@code M22}, so it can be pulled out safely.
 *
 * <p>Destructive while a print is running from the card - the reader is the job's source, so this
 * ends the print somewhere arbitrary, and Marlin does not refuse it. The page disables the button
 * while the last status was {@code SdPrinting}; not enforced here too, since this record does not
 * have that state and a second, weaker copy of the same rule would add nothing.
 */
public record ReleaseCard() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new ReleaseSDCard());
    }
}
