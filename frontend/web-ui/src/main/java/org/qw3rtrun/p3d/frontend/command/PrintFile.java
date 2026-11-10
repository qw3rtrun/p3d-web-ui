package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SelectSDFile;
import org.qw3rtrun.p3d.g.marlin.command.StartOrResumeSDPrint;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Print a file that is already on the card - {@code M23} to select it, {@code M24} to start.
 *
 * <p>Two commands, and no longer because one is impossible: {@code :gcode}'s
 * {@code SelectAndStart} has carried a filename since #37, so a single {@code M32 <name>} is
 * expressible. This pair is what {@code /sd/print} sends today; which of the two forms it should
 * send is <a href="https://github.com/qw3rtrun/p3d-web-ui/issues/109">#109</a>, and is not settled
 * here.
 *
 * <p>Costs nothing in correctness - {@code PrinterControl.send} takes the list as one unit on the
 * aggregate's thread, so nothing lands between the select and the start - and is arguably the better
 * pair anyway: {@code M23} answers {@code File opened: <name> Size: <n>}, reaching the browser as
 * {@code SdFileOpened}, which is where a progress bar gets its denominator.
 *
 * @param name the file to print, as the listing spelled it
 */
public record PrintFile(String name) implements PrinterCommand {

    // See SdFileName - a filename reaches the printer verbatim, so it is checked first.
    public PrintFile {
        name = SdFileName.checked(name);
    }

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SelectSDFile(name), new StartOrResumeSDPrint());
    }
}
