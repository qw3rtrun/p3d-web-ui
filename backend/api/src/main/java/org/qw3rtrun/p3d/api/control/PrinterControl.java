package org.qw3rtrun.p3d.api.control;

import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.reactivestreams.Publisher;

import java.util.List;
import java.util.UUID;

/**
 * Talking to one machine: what it is doing, and what it should do next.
 *
 * <p>The seam between the web surface and the runtime - {@code frontend} used to call
 * {@code PrinterReactorManager} in {@code core} directly, a submodel depending on another
 * (rule 1 of {@code doc/sub_boundaries.txt}); now the controllers name this instead.
 *
 * <p>The vocabulary is {@code :gcode}'s: a command is a {@link GRq}, a reading is a
 * {@link HeaterReading}. This farm drives Marlin machines and Marlin is G-code, so there is no
 * second vocabulary to keep in step; what the browser posts is an HTTP payload translated in
 * {@code frontend} before it reaches here.
 *
 * <p>Methods return {@code Publisher} rather than {@code Mono}/{@code Flux} so Reactor stays off
 * the compile path of every module depending on this one - {@code org.reactivestreams} is the
 * interop SPI those types already implement, not a framework of its own.
 */
public interface PrinterControl {

    /** The last hotend reading this machine reported, as the firmware stated it. */
    Publisher<HeaterReading> state(UUID id);

    /**
     * Everything the machine says, as it says it. Stays open until the machine is released or the
     * caller lets go - what the browser's {@code EventSource} is attached to.
     */
    Publisher<EventMessage> updates(UUID id);

    /**
     * The raw wire lines of the machine's <em>live session</em>, each as a
     * {@link org.qw3rtrun.p3d.api.msg.TerminalTraffic TerminalTraffic} inside an
     * {@link EventMessage} - what a console is attached to. Stays open on the same terms as
     * {@link #updates(UUID)}.
     *
     * <p><strong>A separate stream, not a filtered view of {@link #updates(UUID)}</strong>, for
     * three reasons:
     * <ul>
     *   <li>volume - censused at 106 received lines in one session (see
     *       {@code TerminalTraffic}), so folding them in would roughly double the monitor stream's
     *       traffic for a tab nobody has open;</li>
     *   <li>reach - {@code updates()} is fanned out to the farm-wide dashboard stream, where one
     *       machine's wire traffic has no business being;</li>
     *   <li>scope - the console is the live session only. Nothing here is persisted anywhere
     *       (issue #135 point 3).</li>
     * </ul>
     *
     * <p>Replays a bounded scrollback where {@code updates()} replays nothing - which is exactly
     * why {@code TerminalTraffic} carries a timestamp: a replayed line has to be legible as
     * history rather than as news.
     */
    Publisher<EventMessage> traffic(UUID id);

    /**
     * Sends commands, completing when they are handed over - not when the machine is done.
     *
     * <p>A list because one user action is not always one command: a relative jog is
     * {@code G91}, the move, then {@code G90} back, applied as one task on the aggregate's thread
     * so nothing else can land between them and be read in the wrong mode.
     */
    Publisher<Void> send(UUID id, List<GRq<?>> commands);

    /**
     * Sends one raw line exactly as given, completing when the line has been <strong>accepted into
     * the send queue</strong> - not when the send window has written it, and not when the machine
     * has answered it. The console's input, and the one path on which a line is not interpreted.
     *
     * <p><strong>The proof that it reached the wire is the {@code SENT} line on
     * {@link #traffic(UUID)}</strong>, framed as it went out, and that is deliberately the only
     * proof. Completing on the write would be an unbounded wait rather than a slower answer - there
     * is no timeout on the path, so a machine that has stopped acknowledging stalls the send window
     * and this never completes at all, which an operator cannot tell apart from a line that was
     * refused. A line typed while an SD upload holds the queue exclusively therefore still waits for
     * the transfer before it goes out; it just does not hold a request open meanwhile. Same
     * reasoning as {@link #uploadToCard}, which answers whether a transfer was taken on rather than
     * whether it finished, and as {@link #send}, which never waits for the wire at all.
     *
     * <p><strong>Deliberately does not cross the printer aggregate</strong>, which a reader will
     * reasonably take for a bug against this layer's standing rule. The aggregate's contribution is
     * <em>interpretation</em>, and this line must not be interpreted: decoding it to a {@code GRq}
     * and re-encoding would change what the operator typed - measured, a firmware answers
     * {@code M92 X81} with {@code X81.00}, and {@code :gcode}'s one documented lossy round trip
     * re-encodes {@code M104 F} as {@code M104}. And the aggregate's thread is where every reply is
     * applied, so a diagnostic line typed while a 24-line {@code M503} dump is being processed would
     * queue behind it - the same trade {@code PrinterReactor} already exempts
     * {@code blocksInFlight()}, {@code linked()} and {@code snapshot()} from by name. The send
     * window is <strong>not</strong> protected by the aggregate's thread (it is protected by
     * {@code HostTerminal.CommandProcessor}'s own drain loop, and the queue in front of it by
     * {@code PublisherQueue}'s), so a second send path cannot corrupt it.
     *
     * <p>Refuses rather than throwing, three ways:
     * {@link ControlOutcome.Refusal#ABSENT} for a machine the farm does not hold,
     * {@link ControlOutcome.Refusal#MALFORMED} for a line that must not reach a wire, and
     * {@link ControlOutcome.Refusal#WRONG_STATE} for a machine with no link.
     *
     * @param line one line of G-code as typed, without its {@code N} field or checksum - the send
     *             window frames it, and what actually went out is reported on {@link #traffic(UUID)}
     */
    Publisher<ControlOutcome> sendRaw(UUID id, String line);

    /**
     * Writes a stored G-code file onto the machine's SD card, answering whether it was taken on -
     * not whether the transfer finished, which can take minutes. Progress and completion arrive as
     * {@code SdUploadProgressed} on {@link #updates(UUID)}.
     *
     * <p>Refuses rather than throwing: {@link ControlOutcome.Refusal#ABSENT} for a file the store
     * does not hold, {@link ControlOutcome.Refusal#WRONG_STATE} for a machine that is not
     * connected. {@code fileId} names a file already uploaded to the server's store (a separate
     * act from sending it to a card, so one file can go to several machines); {@code M28} turns
     * the wire into a file sink until the transfer ends, holding the connection exclusively.
     *
     * <p>Two modes, chosen by the caller and refused rather than downgraded: {@code binary} asks
     * for Marlin's Binary Protocol ({@code M28 B1}, packets, then a remount), which copies the
     * file byte-for-byte and needs {@code Cap:BINARY_FILE_TRANSFER}
     * ({@link org.qw3rtrun.p3d.api.model.MachineProfile#hasBinaryFileTransfer()}); a machine that
     * never reported it is {@link ControlOutcome.Refusal#WRONG_STATE}, because silently falling
     * back would report success for something the operator did not ask for. There is no overload:
     * one way to ask means no caller can get the other mode by accident.
     *
     * <p><strong>{@code trim} is only consultable on a binary transfer</strong>, because the ASCII
     * write loop already strips comments and blank lines on every transfer and unconditionally
     * ({@code GcodeLines.blockOf} in {@code :backend:core}) - a line that says nothing would
     * otherwise spend a line number and a slot in the printer's buffer. So this asks a <em>binary</em>
     * transfer to send that same stripped content instead of the stored file verbatim, and the ASCII
     * path strips whatever this says. It is host-side behaviour throughout, gated by no capability
     * and refused by nothing. What actually happened comes back as
     * {@code SdUploadProgressed.trimmed()}, which is {@code true} on every ASCII transfer as a matter
     * of fact rather than of choice.
     *
     * @param cardName what to call the file on the card - what will be listed, printed and deleted
     * @param binary   whether to use the binary protocol rather than the ASCII write loop
     * @param trim     whether a binary transfer should strip comments and blank lines rather than
     *                 copy the stored bytes. {@code false} is the default over the wire, the same
     *                 reasoning {@code binary} carries: a client that posts a body without the field
     *                 gets the behaviour it was written for, and byte-exactness is what a binary
     *                 transfer is for
     */
    Publisher<ControlOutcome> uploadToCard(UUID id, UUID fileId, String cardName, boolean binary,
                                           boolean trim);

    /**
     * Stops a running transfer, leaving a short but closed file on the card - a stop, not a
     * cancel: the source stops producing and {@code M29} still goes out, closing the file rather
     * than leaving it open for later commands to write into.
     *
     * <p>Nothing to stop is {@link ControlOutcome.Accepted}, not a refusal; the only refusal is
     * {@link ControlOutcome.Refusal#ABSENT}, for a machine the farm does not have.
     */
    Publisher<ControlOutcome> cancelCardUpload(UUID id);

    /**
     * Sends {@code M112} at the one priority above everything else the machine's send queue carries
     * - ahead of a print already streaming and ahead of an SD upload holding the wire exclusively.
     * Completes when the stop is in that queue.
     *
     * <p><strong>The only refusal is {@link ControlOutcome.Refusal#ABSENT}</strong>, for a machine
     * the farm does not hold. There is deliberately no {@code WRONG_STATE}: {@link #send} and
     * {@link #sendRaw} both refuse a machine this layer does not believe is linked, and an emergency
     * stop is the one command still worth putting on the wire when that belief is in doubt - a stop
     * arriving at a dead socket costs nothing, and one withheld because the host was unsure is the
     * whole failure this route exists to prevent.
     *
     * <p>It is <em>not</em> reachable through {@link #send}: a {@code FullShutdown} handed to that
     * route is refused, because an emergency stop queued behind a print is not one.
     */
    Publisher<ControlOutcome> emergencyStop(UUID id);

    /** Connects to the machine, or disconnects from it. */
    Publisher<Void> connect(UUID id, ConnectCmd command);

    /**
     * Where the machine's connection is right now, answered once - the same facts
     * {@code ConnectionStateChanged} carries on {@link #updates(UUID)}, for a caller that wants
     * the current answer rather than every change.
     */
    Publisher<ConnectionStatus> connection(UUID id);
}
