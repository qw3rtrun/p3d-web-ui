package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing about {@link SettingsProbe} that is testable without a printer: that a drain ends.
 *
 * <p>A probe is not a test and none of the others here are run by {@code check} - they need a far
 * end, which is the whole reason they sit outside it. This one stands its far end up in-process, so
 * it has no such excuse, and the defect it guards is not one a reader catches by reading: a line
 * that is suppressed is read and dropped, so it advances neither the kept-line cap nor the silence
 * timeout, and against a printer that auto-reports faster than the read window both of
 * {@code drainUntilQuiet}'s exits became unreachable and the probe hung on its first statement
 * (issue #92).
 *
 * <p>The far end here is that printer: a banner, then a {@link org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs}
 * line every 10ms against a 200ms read timeout - the same ratio as the measured ~115ms against
 * 1.5s, scaled so the test costs a fraction of a second. The budget is a parameter of
 * {@code drain} precisely so this can be stated in milliseconds; the probe's own 6s and 20s are
 * the same mechanism with numbers chosen for a real socket.
 */
class SettingsProbeDrainTest {

    private static final long BUDGET_MS = 400;

    // SEPARATE_THREAD, because the failure being guarded against is a blocked read: the default
    // same-thread timeout is only reported once the test returns, which is never.
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aDrainEndsAgainstAPrinterThatAutoReportsFasterThanTheReadWindow() throws Exception {
        var stop = new AtomicBoolean();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var flood = autoReportingPrinter(server, stop);
            try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                socket.setSoTimeout(200);
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                var startedAt = System.nanoTime();
                List<String> drained = SettingsProbe.drain(in, BUDGET_MS, 2000, line -> false);
                var elapsedMs = NANOSECONDS.toMillis(System.nanoTime() - startedAt);

                // The flood never pauses, so neither of the pre-#92 exits can fire: the list stops
                // growing at the banner and the socket is never silent for 200ms.
                assertEquals(List.of("start"), drained,
                        "every line after the banner is an auto-report and is kept out of the transcript");
                assertTrue(elapsedMs < BUDGET_MS * 10,
                        "the drain must end on its wall-clock budget, not on silence that never comes; took "
                                + elapsedMs + "ms");
            } finally {
                stop.set(true);
                flood.join();
            }
        }
    }

    /** Writes a banner once and then a bare temperature report every 10ms, for ever. */
    private static Thread autoReportingPrinter(ServerSocket server, AtomicBoolean stop) {
        var printer = new Thread(() -> {
            try (var socket = server.accept();
                 var out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                out.write("start\n");
                out.flush();
                while (!stop.get()) {
                    out.write("T:25.00 /0.00 B:25.00 /0.00 @:0 B@:0\n");
                    out.flush();
                    Thread.sleep(10);
                }
            } catch (Exception gone) {
                // the drain returned and closed its end, which is the pass
            }
        });
        printer.setDaemon(true);
        printer.start();
        return printer;
    }
}
