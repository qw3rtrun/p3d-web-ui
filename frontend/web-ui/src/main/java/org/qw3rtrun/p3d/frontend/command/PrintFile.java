package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SelectSDFile;
import org.qw3rtrun.p3d.g.marlin.command.StartOrResumeSDPrint;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Print a file that is already on the card - {@code M23} to select it, {@code M24} to start.
 *
 * <p><strong>Two commands by choice.</strong> {@code :gcode}'s {@code SelectAndStart} has carried a
 * filename since #37, so one {@code M32 <name>} is expressible;
 * <a href="https://github.com/qw3rtrun/p3d-web-ui/issues/109">#109</a> measured it and rejected it.
 * {@code M32}'s signature is {@code M32 [P<value>] [S<filepos>] [<filename>]} and - unlike
 * {@code M23}, which takes the rest of its line verbatim - it is not in Marlin's string-argument
 * special case, so the firmware parses the line for parameters first and reads a {@code p}
 * <em>anywhere</em> in the filename as {@code P}: call this file as a subroutine, pushing whatever
 * file is open as a parent and returning to it when the print ends. With {@code live.gco} left open
 * but not started, one request to print {@code cup.gco} printed both:
 *
 * <pre>
 * &gt;&gt; N39 M32 cup.gco*43
 * &lt;&lt; echo:SUBROUTINE CALL target:"cup.gco" parent:"/LIVE.GCO" pos0
 * &lt;&lt; RAN-cup.gco
 * &lt;&lt; RAN-live.gco
 * &lt;&lt; Done printing file
 * </pre>
 *
 * <p>MarlinSim, 2026-11-10, by {@code SdPrintProbe} in {@code :backend:terminal}
 * ({@code -PprobeOrder=names}); the {@code RAN-} lines are the files' own {@code M118 P0} contents,
 * so both really ran. Position in the name is irrelevant - {@code prt.gco} and {@code cup.gco}
 * answer {@code echo:Now doing file:}, Marlin's subroutine announcement, where {@code live.gco} and
 * {@code set.gco} answer {@code echo:Now fresh file:} - and {@code s} is harmless, because
 * {@code S} requires a value where {@code P} does not. Marlin's documented {@code !name#} spelling
 * does suppress it, and is deliberately not adopted: a name containing {@code #} could then not be
 * delimited, and {@code #} is legal in an 8.3 name.
 *
 * <p>The pair costs two things, both real and both smaller: {@code M24} after an {@code M23} is a
 * <em>resume</em> ({@code //action:resume}, and a {@code //action:prompt_begin Resuming SD} prompt
 * sequence), which is cosmetically wrong; and a lost {@code M24} has no symptom, because
 * {@code M27} after the select alone already answers {@code SD printing byte 0/<size>} rather than
 * {@code Not SD printing}. Neither is paid in correctness here - {@code PrinterControl.send} takes
 * the list as one unit on the aggregate's thread, so nothing lands between the select and the start
 * - and the reply set was never the deciding factor either: {@code M32} opens and selects exactly
 * as {@code M23} does, so {@code File opened: <name> Size: <n>} reaches the browser as
 * {@code SdFileOpened}, which is where a progress bar gets its denominator, under either form.
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
