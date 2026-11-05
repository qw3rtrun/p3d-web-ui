package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.InitSDCard;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Mount the SD card - {@code M21}.
 *
 * <p>Also how a host finds out whether there is a card, since no command asks that directly: a
 * machine with one answers {@code echo:SD card ok}, reaching the browser as {@code SdCardOk}; the
 * Storage tab reads anything else as "no card".
 *
 * <p>The failure text itself is not pinned - the simulator always has a card, so it cannot be
 * captured there, and guessing at it from the wiki is how this module got reply formats wrong
 * before. Treating the absence of the success line as failure needs no such guess.
 *
 * <p>Carries nothing, so this is a record with no components rather than an empty body - the
 * browser still posts {@code {}}, and the shape stays a body like every write on this surface.
 */
public record MountCard() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new InitSDCard());
    }
}
