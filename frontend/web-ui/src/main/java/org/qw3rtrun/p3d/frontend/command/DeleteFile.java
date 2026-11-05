package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.DeleteSDFile;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Delete a file from the card - {@code M30 <name>}.
 *
 * <p>Irreversible and unconfirmed by the machine, so the confirmation is the page's.
 *
 * <p>The name is the card's spelling, not the user's: a file uploaded as {@code benchy.gco} is
 * listed as {@code BENCHY.GCO} on a build without long-filename support, and that is the name
 * {@code M30} takes - so the page sends back whatever the listing gave it.
 *
 * @param name the file to delete, as the listing spelled it
 */
public record DeleteFile(String name) implements PrinterCommand {

    // See SdFileName - a filename reaches the printer verbatim, so it is checked first.
    public DeleteFile {
        name = SdFileName.checked(name);
    }

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new DeleteSDFile(name));
    }
}
