package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.ListSDCard;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * List the printable files on the card - {@code M20}.
 *
 * <p>The answer arrives on the event stream as one {@code SdFileList}, assembled by the transport
 * from the three or more lines the firmware writes - the entry lines are not claimable replies on
 * their own.
 *
 * <p>{@code M20} filters by extension: Marlin lists only what it considers printable ({@code
 * .gcode}, {@code .gco}, {@code .g}), so a file uploaded under any other name is on the card and
 * printable by exact name but never appears here. Measured, not assumed: a probe wrote a file called
 * {@code test}, got an empty listing, and the first read was that the simulator was broken - it
 * was not.
 *
 * <p>The bare form is sent: {@code L}/{@code T} ask for long names and timestamps, which this
 * firmware reports {@code Cap:LONG_FILENAME:0} for, so asking would only make two machines answer
 * differently for no gain.
 */
public record ListFiles() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new ListSDCard());
    }
}
