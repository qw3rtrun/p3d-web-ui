package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SelectSDFile;
import org.qw3rtrun.p3d.g.marlin.command.StartOrResumeSDPrint;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Print a file that is already on the card - {@code M23} to select it, {@code M24} to start.
 *
 * <p><strong>Two commands rather than {@code M32}, and that is a gap and not a preference.</strong>
 * {@code M32} selects and starts in one, which is what you would reach for - but {@code :gcode}'s
 * {@code SelectAndStart} cannot carry a filename. It is generated from Marlin's own documented
 * signature, {@code M32 [P<value>] [S<filepos>]}, which omits the {@code !name#} the command
 * actually takes, so the generated class has {@code p} and {@code filepos} and nowhere to put the
 * name. Regenerating it needs the source document fixed upstream; until then this is the route that
 * works with the classes as they are.
 *
 * <p>It costs nothing in correctness: {@code PrinterControl.send} takes the list as <em>one unit</em>
 * applied on the aggregate's thread, so nothing can land between the select and the start. It is in
 * fact the better-behaved pair for a UI, because {@code M23} answers
 * {@code File opened: <name> Size: <n>} - which reaches the browser as {@code SdFileOpened} and is
 * where a progress bar gets its denominator.
 *
 * @param name the file to print, as the listing spelled it
 */
public record PrintFile(String name) implements PrinterCommand {

    /** See {@link SdFileName} - a filename reaches the printer verbatim, so it is checked first. */
    public PrintFile {
        name = SdFileName.checked(name);
    }

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SelectSDFile(name), new StartOrResumeSDPrint());
    }
}
