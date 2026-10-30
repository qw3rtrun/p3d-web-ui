package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.ReleaseSDCard;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Release the SD card - {@code M22}, so it can be pulled out safely.
 *
 * <p><strong>Destructive while a print is running from the card</strong>: the reader is the source
 * of the job, so releasing it mid-print ends the print somewhere arbitrary. Marlin does not refuse
 * it. The page is what keeps that from happening - the button is disabled while the last status
 * seen was {@code SdPrinting} - and that is a decision the UI can make because it has the status
 * and this record does not.
 *
 * <p>Not enforced here on purpose. A record that refused to translate itself would be a second,
 * weaker copy of a rule the page already applies, placed where it cannot see the state it needs.
 */
public record ReleaseCard() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new ReleaseSDCard());
    }
}
