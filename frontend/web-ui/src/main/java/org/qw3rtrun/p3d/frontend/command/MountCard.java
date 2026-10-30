package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.InitSDCard;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Mount the SD card - {@code M21}.
 *
 * <p><strong>It is also how a host finds out whether there is a card at all</strong>, because no
 * command asks that question. A machine with a card answers {@code echo:SD card ok}, which reaches
 * the browser as an {@code SdCardOk} event; one without answers something else, and the Storage tab
 * reads anything that is not that as "no card".
 *
 * <p>The exact text of the failure is <strong>not pinned</strong>: the simulator always has a card,
 * so it cannot be captured there, and guessing at it from the wiki is how this module got reply
 * formats wrong before. Treating the absence of the success line as failure needs no such guess.
 *
 * <p>Carries nothing, so it is a record with no components rather than an empty body: the browser
 * posts {@code {}} and the shape stays a body, which is what every write on this surface is.
 */
public record MountCard() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new InitSDCard());
    }
}
