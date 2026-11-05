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
     * Sends commands, completing when they are handed over - not when the machine is done.
     *
     * <p>A list because one user action is not always one command: a relative jog is
     * {@code G91}, the move, then {@code G90} back, applied as one task on the aggregate's thread
     * so nothing else can land between them and be read in the wrong mode.
     */
    Publisher<Void> send(UUID id, List<GRq<?>> commands);

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
     * @param cardName what to call the file on the card - what will be listed, printed and deleted
     */
    Publisher<ControlOutcome> uploadToCard(UUID id, UUID fileId, String cardName);

    /**
     * Stops a running transfer, leaving a short but closed file on the card - a stop, not a
     * cancel: the source stops producing and {@code M29} still goes out, closing the file rather
     * than leaving it open for later commands to write into.
     *
     * <p>Nothing to stop is {@link ControlOutcome.Accepted}, not a refusal; the only refusal is
     * {@link ControlOutcome.Refusal#ABSENT}, for a machine the farm does not have.
     */
    Publisher<ControlOutcome> cancelCardUpload(UUID id);

    /** Connects to the machine, or disconnects from it. */
    Publisher<Void> connect(UUID id, ConnectCmd command);

    /**
     * Where the machine's connection is right now, answered once - the same facts
     * {@code ConnectionStateChanged} carries on {@link #updates(UUID)}, for a caller that wants
     * the current answer rather than every change.
     */
    Publisher<ConnectionStatus> connection(UUID id);
}
