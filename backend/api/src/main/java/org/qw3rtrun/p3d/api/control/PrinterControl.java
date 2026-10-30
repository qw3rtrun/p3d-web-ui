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
 * <p>The seam between the web surface and the runtime. {@code frontend} used to call
 * {@code PrinterReactorManager} in {@code core} directly, which is a submodel depending on another
 * submodel - rule 1 of {@code doc/sub_boundaries.txt} - and the boundary check could only report it,
 * not prevent it. Now the controllers name this, and {@code core} implements it.
 *
 * <p><strong>The vocabulary is {@code :gcode}'s.</strong> A command is a {@link GRq} and a reading
 * is a {@link HeaterReading}, because this farm drives Marlin machines and Marlin is G-code: an
 * abstraction over it would be a second set of names for one set of facts. What the browser posts
 * is a different question - that is an HTTP payload, it lives in {@code frontend}, and the
 * controller translates it before it gets here.
 *
 * <p><strong>{@code Publisher} and not {@code Mono}/{@code Flux}.</strong> These methods are
 * inherently asynchronous - one of them is a stream that stays open for as long as a browser
 * watches a printer - so the type has to say so. Reactor's own types would put Reactor on the
 * compile path of every module that depends on this one. {@code org.reactivestreams} is the interop
 * SPI those types implement rather than a framework: a {@code Flux} already <em>is</em> a
 * {@code Publisher}, and WebFlux already accepts one, so neither side adapts anything.
 */
public interface PrinterControl {

    /** The last hotend reading this machine reported, as the firmware stated it. */
    Publisher<HeaterReading> state(UUID id);

    /**
     * Everything the machine says, as it says it.
     *
     * <p>Stays open until the machine is released or the caller lets go, which is what the
     * browser's {@code EventSource} is attached to.
     */
    Publisher<EventMessage> updates(UUID id);

    /**
     * Sends commands, and completes when they have been handed over - not when the machine is done.
     *
     * <p><strong>A list, because one thing a user asks for is not always one command.</strong> A
     * relative jog is {@code G91}, the move, and {@code G90} back again; the runtime applies the
     * whole list as one task on the aggregate's thread, so nothing can land between them and be
     * read in the wrong mode.
     */
    Publisher<Void> send(UUID id, List<GRq<?>> commands);

    /**
     * Writes a stored G-code file onto the machine's SD card, and completes when it is <em>queued</em>.
     *
     * <p><strong>It does not complete when the transfer does</strong>, and it must not: every line
     * of an upload is a round trip inside the send window, so a real file takes minutes. A request
     * held open for that is a request that times out. What this answers is "the transfer started";
     * what answers "it finished" is {@code SdUploadProgressed} on {@link #updates(UUID)}, which is
     * where the progress goes too.
     *
     * <p><strong>The file is one already on the server</strong>, named by {@code fileId} - this
     * takes no bytes. The browser uploads to {@code /api-files} first, which streams to a
     * content-addressed store and records the size an upload needs for its denominator; sending it
     * to a card is a second, separate act, and the same file can go to several machines without
     * being uploaded again.
     *
     * <p><strong>The machine takes nothing else until it is over.</strong> {@code M28} turns the
     * wire into a file sink - every line that follows is written into the file rather than executed
     * - so the transfer holds the connection exclusively, and an emergency stop is the only thing
     * that can take it away.
     *
     * @param id       the machine
     * @param fileId   a file in the server's store
     * @param cardName what to call it on the card, which is what will be listed, printed and
     *                 deleted afterwards
     */
    Publisher<Void> uploadToCard(UUID id, UUID fileId, String cardName);

    /**
     * Stops a transfer that is running, leaving a short but closed file on the card.
     *
     * <p><strong>It is a stop and not a cancel</strong>, and the difference is the one thing that
     * matters here: the source is asked to stop producing content, and {@code M29} still goes out
     * behind it. Tearing the transfer out instead would leave the file <em>open</em>, and every
     * command the machine was given next would be written into it.
     *
     * <p>Completes whether or not a transfer was running. Nothing to stop is not an error - the
     * page's button and the machine's state are different clocks.
     */
    Publisher<Void> cancelCardUpload(UUID id);

    /** Connects to the machine, or disconnects from it. */
    Publisher<Void> connect(UUID id, ConnectCmd command);

    /**
     * Where the machine's connection is right now - the health signal, answered once.
     *
     * <p>The same facts {@code ConnectionStateChanged} carries on {@link #updates(UUID)}, for a
     * caller that wants the current answer rather than every change. A page rendering a printer
     * needs this before it has seen a single event, and before B3 it had nothing to ask: the only
     * way to learn whether a machine was connected was to open the stream and wait for something
     * to happen on it.
     */
    Publisher<ConnectionStatus> connection(UUID id);
}
