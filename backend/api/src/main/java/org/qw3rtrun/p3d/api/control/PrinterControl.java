package org.qw3rtrun.p3d.api.control;

import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.PrinterCommand;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.reactivestreams.Publisher;

import java.util.UUID;

/**
 * Talking to one machine: what it is doing, and what it should do next.
 *
 * <p>The seam between the web surface and the runtime. {@code frontend} used to call
 * {@code PrinterReactorManager} in {@code core} directly, which is a submodel depending on another
 * submodel - rule 1 of {@code doc/sub_boundaries.txt} - and the boundary check could only report it,
 * not prevent it. Now the controllers name this, and {@code core} implements it.
 *
 * <p><strong>{@code Publisher} and not {@code Mono}/{@code Flux}.</strong> These methods are
 * inherently asynchronous - one of them is a stream that stays open for as long as a browser
 * watches a printer - so the type has to say so. Reactor's own types would put Reactor on the
 * compile path of every module that depends on this one, {@code printer-management} included, and
 * this module's one real property is that it depends on nothing. {@code org.reactivestreams} is the
 * interop SPI those types implement rather than a framework: a {@code Flux} already <em>is</em> a
 * {@code Publisher}, and WebFlux already accepts one, so neither side adapts anything.
 */
public interface PrinterControl {

    /** The last temperatures this machine reported. */
    Publisher<TemperatureReport> state(UUID id);

    /**
     * Everything the machine says, as it says it.
     *
     * <p>Stays open until the machine is released or the caller lets go, which is what the
     * browser's {@code EventSource} is attached to.
     */
    Publisher<EventMessage> updates(UUID id);

    /** Sends one command, and completes when it has been handed over - not when the machine is done. */
    Publisher<Void> send(UUID id, PrinterCommand command);

    /** Connects to the machine, or disconnects from it. */
    Publisher<Void> connect(UUID id, ConnectCmd command);
}
