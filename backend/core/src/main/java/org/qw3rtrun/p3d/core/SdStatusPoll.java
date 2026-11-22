package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.g.marlin.command.ReportSDPrintStatus;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * Asks every connected, SD-capable machine {@code M27} again, every three seconds - because on this
 * farm's printers nothing else ever will. Issue #147.
 *
 * <h2>Why a host-side timer and not {@code M27 S3}</h2>
 * Marlin can report print status on its own, and {@code PrinterState.startReportsFor} subscribes to
 * it with {@code M27 S3} where the firmware says it can. These machines say it cannot. Probed
 * 2026-11-21 with {@code :backend:terminal:probeHandshake} against the live simulator on
 * {@code localhost:8099}: {@code Cap:AUTOREPORT_SD_STATUS:0}, and {@code M27 S1} sent anyway was
 * <em>accepted</em> - byte-identical to a bare {@code M27} - and then produced <strong>0 unsolicited
 * lines in 3400 ms</strong>. There is no signal on the wire to learn from, which is the measurement
 * {@code AutoReportControl}'s javadoc records and the reason the handshake reads the {@code Cap:}
 * line rather than asking.
 *
 * <p>So the one-shot form is sent repeatedly instead. Without this, {@code M27} went out exactly
 * once per session - from the {@code Cap:SDCARD} branch of {@code PrinterState.startReportsFor} -
 * and a print's progress ({@code SdPrinting(position, size)}) was whatever that single read found,
 * for the rest of the session, however the print was started: from the Storage tab's Print button
 * ({@code M23}+{@code M24}), or from the printer's own screen, which the host hears about through
 * nothing but {@code M27}.
 *
 * <p>Three seconds is not a new number: it is the period
 * {@code PrinterState.startReportsFor} already chose for {@code M27 S3} on firmware that supports
 * the subscription, for the reason stated there - a percentage does not move at 1 Hz, and every
 * report is a message on every open SSE stream. Configurable as
 * {@code p3d.printer.sd-status-poll-interval}, the way {@code JobFeeder.reportProgress} takes
 * {@code p3d.jobs.progress-interval}.
 *
 * <p>It adds no event type and no emission: {@code SdCardControl} already folds the answer into the
 * aggregate's state and {@code PrinterState.on(SdCardRs)} already emits every card reply, so it
 * reaches {@code PrinterControl.updates(id)} and the farm-wide stream as it arrives. On the wire a
 * pass is indistinguishable from a browser asking once.
 *
 * <h2>Threading, and a pass at forty machines</h2>
 * A {@code @Scheduled} method on a singleton, so a pass runs on a {@code scheduling-} thread and
 * nothing here blocks. Each call it makes is already reached from several threads elsewhere:
 * {@code runtimeOf} is {@code synchronized} around a map lookup, {@code linked()} and
 * {@code profile()} are volatile reads answered from the caller's thread by contract, and
 * {@code handle(List)} hops onto the machine's own executor itself. So forty printers cost forty
 * volatile reads and at most forty one-line commands every three seconds, each onto its own
 * machine's queue, with nothing shared but the monitor.
 *
 * <p><strong>The {@code Mono} is subscribed</strong>, which is the whole of why this class has a
 * test: {@code PrinterReactor.handle} is cold, so a pass that merely composes it asks nothing of
 * anybody - the dead-feature failure this layer has shipped three times.
 *
 * <p>Known cost, named rather than fixed: a machine that <em>does</em> report
 * {@code Cap:AUTOREPORT_SD_STATUS:1} has {@code M27 S3} running and gets this poll as well, so it
 * answers twice as often as it needs to. No machine in this farm does, and suppressing it would mean
 * a second capability accessor on {@code MachineProfile} for a case nothing has measured.
 */
@Slf4j
@RequiredArgsConstructor
public class SdStatusPoll {

    private final PrinterDirectory directory;
    private final PrinterReactorManager reactors;

    @Scheduled(fixedDelayString = "${p3d.printer.sd-status-poll-interval:3000}")
    public void askEveryCard() {
        for (var entry : directory.all()) {
            var reactor = reactors.runtimeOf(entry.id());
            if (reactor == null || !reactor.linked() || !reactor.profile().hasSdCard()) {
                continue;
            }
            reactor.handle(List.of(new ReportSDPrintStatus()))
                    .subscribe(ignored -> {
                    }, cause -> log.debug("the SD status poll of {} ended in {}",
                            entry.id(), cause.toString()));
        }
    }
}
