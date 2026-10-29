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
