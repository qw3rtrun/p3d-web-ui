package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.ListSDCard;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * List the printable files on the card - {@code M20}.
 *
 * <p><strong>The answer does not come back here</strong>, like every reply in this application: it
 * arrives on the event stream as one {@code SdFileList}, assembled by the transport from the three
 * or more lines the firmware writes. Nothing downstream could see a listing at all until that was
 * built - the entry lines are deliberately not claimable replies, so they were dropped as unread.
 *
 * <p><strong>{@code M20} filters by extension, and the browser has to know.</strong> Marlin lists
 * only what it considers printable - {@code .gcode}, {@code .gco}, {@code .g} - so a file uploaded
 * under any other name is on the card, is printable by exact name, and never appears here. That was
 * measured rather than read: a probe wrote a file called {@code test}, found an empty listing, and
 * the first conclusion drawn was that the simulator was broken. It was not.
 *
 * <p>The bare form is sent. {@code L} and {@code T} ask for long names and timestamps, which this
 * firmware reports {@code Cap:LONG_FILENAME:0} for, and a flag a machine ignores is a flag that
 * makes two machines answer differently for no gain.
 */
public record ListFiles() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new ListSDCard());
    }
}
