package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.PauseSDPrint;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Pause the print already running from the card - {@code M25}.
 *
 * <p>Carries no fields, unlike {@link PrintFile}: there is only ever one print open on the card, so
 * the firmware already knows which one this is about. The pause is the firmware's, held on the
 * machine rather than by this host holding back lines, which is why it survives the host going away
 * and why {@link ResumeSdPrint} is a command rather than a resumption of something kept here.
 */
public record PauseSdPrint() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new PauseSDPrint());
    }
}
