package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.g.marlin.command.PositionAutoReport;
import org.qw3rtrun.p3d.g.marlin.command.ReportSDPrintStatus;
import org.qw3rtrun.p3d.g.marlin.command.TemperatureAutoReport;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of Marlin's three auto-reports <em>this session</em> turned on, so the goodbye can undo
 * exactly those. The fifth control beside {@link TemperatureControl}, {@link SdCardControl},
 * {@link EEPROMSettingsControl} and {@link ProfileControl}, and the one that keeps no reply: it is a
 * record of what the host asked for, not of what the machine said.
 *
 * <p>There are exactly three. {@code Cap:PROGRESS} and {@code Cap:BUILD_PERCENT} are about
 * {@code M73}, which is progress travelling host -&gt; printer, and are not reports.
 *
 * <h2>Why the farm reads the capability instead of asking</h2>
 * Probed 2026-11-17 with {@code :backend:terminal:probeHandshake} against
 * {@code Marlin bugfix-2.1.x (Oct 29 2026 10:28:56)} on {@code localhost:8099}:
 * <pre>
 * Cap:AUTOREPORT_TEMP:1        M155 S1 -&gt; 3 unsolicited ` T:25.00 /0.00 ...` in 3400 ms
 *                              M155 S0 -&gt; 0 in the next 3400 ms            (the control)
 * Cap:AUTOREPORT_POS:0         M154 S1 -&gt; echo:Unknown command: "M154 S1", ok P15 B3; 0 lines
 * Cap:AUTOREPORT_SD_STATUS:0   M27 S1  -&gt; Not SD printing, ok P15 B3;       0 lines
 * </pre>
 * The third line is the measurement the whole feature rests on. {@code M154 S1} on a firmware
 * without it is <em>refused</em>, so "send it and let the firmware say no" would have worked for
 * position - the way {@link EEPROMSettingsControl} learns about {@code M503}. {@code M27 S1} on a
 * firmware without it is <em>accepted</em>, byte-identical to a bare {@code M27}, and then reports
 * nothing ever: there is no signal on the wire at all to learn from. So the gate is the
 * {@code Cap:} line and never the reply.
 *
 * <p>And note the spelling on the wire: <strong>{@code AUTOREPORT_POS}</strong>. Marlin's build
 * macro is {@code AUTOREPORT_POSITION} and the G-code is {@code M154}; no line under any longer
 * spelling appears among the simulator's 38, so a host keyed on {@code AUTOREPORT_POSITION} finds
 * nothing and says nothing.
 *
 * <h2>Concurrency</h2>
 * A plain field, confined to this machine's own {@code p3d-printer-<uuid>} thread - unlike the other
 * four controls, which are {@code volatile} because {@link PrinterState#snapshot()} reads them from
 * whichever thread subscribes. Nothing reads this one off the aggregate's thread: every caller is
 * {@code PrinterState.onOnline}, {@code on(CapabilitiesReport)} or {@code quiesce()}, and
 * {@link PrinterReactor} reaches all three through its executor. A {@code volatile} here would be
 * cargo-culted from the neighbours. If a reader on another thread ever turns up, that is a design
 * change - the question being "who may ask what this machine is reporting" - and not a field
 * modifier.
 *
 * <p>Deliberately <strong>no {@code snapshot()}</strong>: whether auto-reports are running is not a
 * fact a page needs, and putting it in {@link PrinterState#snapshot()} would add a message type to
 * every SSE stream for nobody's benefit. This feature is invisible to the browser by design.
 */
public class AutoReportControl {

    /** One per report, in the order they would be turned off. The off-switch each one needs. */
    private enum Report {

        /** {@code M155 S0}. */
        TEMPERATURE,

        /** {@code M154 S0}. */
        POSITION,

        /** {@code M27 S0}. */
        SD_STATUS;

        GRq<?> off() {
            return switch (this) {
                case TEMPERATURE -> new TemperatureAutoReport(0);
                case POSITION -> new PositionAutoReport(0);
                case SD_STATUS -> new ReportSDPrintStatus(0, false);
            };
        }
    }

    // insertion-ordered so teardown() replays the order the machine's own Cap: lines caused, and a
    // set so starting one twice is still one subscription.
    private Set<Report> started = new LinkedHashSet<>();

    /**
     * A new session on the wire: whatever the last one started went with its socket. A link that was
     * down may return to a reflashed machine, or to a different machine at the same address - see
     * {@link SdCardControl#sessionStarted()} - so the off-switches of the previous session are not
     * this one's to send.
     */
    public void sessionStarted() {
        started = new LinkedHashSet<>();
    }

    public void temperatureStarted() {
        started.add(Report.TEMPERATURE);
    }

    public void positionStarted() {
        started.add(Report.POSITION);
    }

    public void sdStatusStarted() {
        started.add(Report.SD_STATUS);
    }

    /**
     * The commands that turn off what this session turned on, in the order it turned them on, and
     * empty when it started nothing. Reads rather than clears: forgetting is
     * {@link #sessionStarted()}, which {@code PrinterState.quiesce()} calls after sending these.
     */
    public List<GRq<?>> teardown() {
        var off = new ArrayList<GRq<?>>(started.size());
        for (var report : started) {
            off.add(report.off());
        }
        return off;
    }
}
