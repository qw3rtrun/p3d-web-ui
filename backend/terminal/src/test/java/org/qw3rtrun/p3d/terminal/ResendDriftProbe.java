package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.code.core.block.XorCheckSum;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The firmware half of issue #60, on a raw socket, with nothing of this host's stack in the way.
 *
 * <p>{@link ResendOnReconnectProbe} drives the real transport and therefore only reaches the case
 * when the far end happens to be in it. This one <em>puts</em> the far end in it: it pushes the
 * firmware's {@code last_N} to a known value and then sends a block numbered as a fresh session
 * would number it. What comes back is the {@code Error:} / {@code Resend:} pair the issue quotes,
 * measured rather than taken from the issue's three lines.
 *
 * <p>Four questions, in order, and each one decides part of the fix:
 *
 * <ol>
 *   <li><strong>What exactly does a firmware with {@code last_N = 14} answer to {@code N1}?</strong>
 *       In particular, is the number in the {@code Resend:} {@code last_N + 1} - which the send
 *       window has never held and cannot answer - or the number that was rejected, which it can?</li>
 *   <li><strong>Does {@code M110 N0} repair it?</strong> If a single {@code M110} puts the sequence
 *       back, preventing the state is a real fix and not a hope.</li>
 *   <li><strong>Which {@code N} does the firmware read out of an {@code M110} line</strong> - the
 *       line's own, or the parameter? {@code GSendWindow.reset(0)} frames {@code M110 N0} with the
 *       number that was next and then sets the counter so the <em>next</em> block carries the same
 *       number, so on a fresh window the {@code M110} and the block behind it are both {@code N1}.
 *       Sending {@code N7 M110 N0} and then {@code N1} answers it outright.</li>
 *   <li><strong>What does an unanswerable {@code Resend:} look like next to an answerable one?</strong>
 *       A corrupted checksum on a block the firmware is expecting is the recoverable control.</li>
 * </ol>
 *
 * <p>Usage: {@code ResendDriftProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class ResendDriftProbe {

    private ResendDriftProbe() {
    }

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        try (var wire = new Wire(host, port)) {
            wire.settle(1500);
            wire.drain("after connecting");

            System.out.println();
            System.out.println("### 1 - push last_N to 14");
            wire.send(frame(1, "M110 N0"));
            wire.settle(400);
            wire.drain("the opening M110");
            for (int n = 1; n <= 14; n++) {
                wire.send(frame(n, "M105"));
                wire.settle(250);
            }
            wire.drain("fourteen numbered blocks");

            System.out.println();
            System.out.println("### 2 - THE CASE: a fresh session's first block, N1, against last_N = 14");
            wire.send(frame(1, "G21"));
            wire.settle(1200);
            var rejection = wire.drain("N1 G21 with last_N = 14");

            System.out.println();
            System.out.println("    >> Error: lines   " + only(rejection, "Error:"));
            System.out.println("    >> Resend: lines  " + only(rejection, "Resend:"));
            System.out.println("    >> ok lines       " + only(rejection, "ok"));

            System.out.println();
            System.out.println("### 3 - and again twice, which is what a host that cannot answer the Resend does");
            wire.send(frame(1, "G21"));
            wire.settle(900);
            wire.send(frame(1, "G21"));
            wire.settle(900);
            var repeated = wire.drain("the same block twice more");
            System.out.println("    >> Resend: lines  " + only(repeated, "Resend:"));

            System.out.println();
            System.out.println("### 4 - does M110 N0 repair it? And which N does the firmware read from it?");
            // Deliberately numbered 7, which is neither last_N + 1 nor the parameter, so the
            // block that follows tells us which of the two the firmware kept.
            wire.send(frame(7, "M110 N0"));
            wire.settle(600);
            var repaired = wire.drain("N7 M110 N0");
            System.out.println("    >> answer to the M110  " + only(repaired, "ok", "Error:", "Resend:"));

            wire.send(frame(1, "G21"));
            wire.settle(900);
            var afterReset = wire.drain("N1 G21 straight after N7 M110 N0");
            System.out.println("    >> N1 accepted after a reset?  "
                    + (only(afterReset, "Error:").isEmpty() ? "YES - the parameter is what counts"
                    : "NO  - " + only(afterReset, "Error:", "Resend:")));

            System.out.println();
            System.out.println("### 5 - the control: a Resend: the host CAN answer (bad checksum, right number)");
            var good = frame(2, "G90");
            var broken = corrupt(good);
            System.out.println("    sending  " + broken.trim() + "   (should have been " + good.trim() + ")");
            wire.send(broken);
            wire.settle(900);
            var checksum = wire.drain("a block whose checksum is wrong");
            System.out.println("    >> " + only(checksum, "Error:", "Resend:", "ok"));
            wire.send(good);
            wire.settle(900);
            wire.drain("the same block, re-sent correctly");
        }
    }

    /** Frames a payload the way {@code GSendWindow} does, so these are this host's own bytes. */
    private static String frame(int number, String payload) {
        return GEncoder.INSTANCE.frame(number, payload, new XorCheckSum()) + "\n";
    }

    private static String corrupt(String block) {
        int star = block.lastIndexOf('*');
        int checksum = Integer.parseInt(block.substring(star + 1).trim());
        return block.substring(0, star) + "*" + ((checksum + 1) & 0xFF) + "\n";
    }

    private static List<String> only(List<String> lines, String... prefixes) {
        var kept = new ArrayList<String>();
        for (var line : lines) {
            for (var prefix : prefixes) {
                if (line.startsWith(prefix)) {
                    kept.add(line);
                    break;
                }
            }
        }
        return kept;
    }

    /** The socket, and everything that has come off it since it was last drained. */
    private static final class Wire implements AutoCloseable {

        private final Socket socket;
        private final OutputStream out;
        private final List<String> received = new ArrayList<>();
        private final Thread reader;

        Wire(String host, int port) throws Exception {
            this.socket = new Socket(host, port);
            this.out = socket.getOutputStream();
            var in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            this.reader = new Thread(() -> {
                try {
                    for (String line; (line = in.readLine()) != null; ) {
                        var text = line.trim();
                        if (!text.isEmpty()) {
                            synchronized (received) {
                                received.add(text);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // the socket closed under us, which is how this thread ends
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        void send(String block) throws Exception {
            System.out.printf("  -> %s", block);
            out.write(block.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        void settle(long millis) throws InterruptedException {
            TimeUnit.MILLISECONDS.sleep(millis);
        }

        /**
         * Everything heard since the last call, printed - minus the autoreported temperatures,
         * which this firmware emits several times a second and which drown everything else.
         */
        List<String> drain(String what) {
            List<String> lines;
            synchronized (received) {
                lines = List.copyOf(received);
                received.clear();
            }
            var kept = new ArrayList<String>();
            int temperatures = 0;
            for (var line : lines) {
                if (line.startsWith("T:") || line.startsWith("@:") || line.startsWith("B:")) {
                    temperatures++;
                    continue;
                }
                kept.add(line);
            }
            System.out.printf("  <- [%s] %d line(s), %d autoreported temperatures dropped%n",
                    what, kept.size(), temperatures);
            kept.forEach(l -> System.out.println("     " + l));
            return kept;
        }

        @Override
        public void close() throws Exception {
            socket.close();
            reader.interrupt();
        }
    }
}
