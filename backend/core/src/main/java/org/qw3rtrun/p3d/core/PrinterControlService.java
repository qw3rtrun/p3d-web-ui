package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.protocol.GRq;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

import static org.qw3rtrun.p3d.g.code.dsl.GRawBlockKt.rawBlock;

/**
 * This module's side of {@link PrinterControl}: the only way in from the web.
 *
 * <p>It exists so that {@code frontend} can stop naming {@code PrinterReactorManager}. A submodel
 * depending on another submodel is rule 1 of {@code doc/sub_boundaries.txt}, and the boundary check
 * could report that crossing but not prevent it.
 *
 * <p><strong>Nothing is translated here any more.</strong> This class used to hold a
 * {@code toMarlin} switch over a sealed {@code api.msg.PrinterCommand}, the outbound half of a
 * mirror of {@code :gcode} that {@code ReplyEvents} kept on the inbound side. Both are gone: a
 * command arrives as the {@code GRq} it is, and the one place that still turns a browser's words
 * into Marlin's is the controller that received them, because a request body is the web surface's
 * business and not the runtime's.
 */
@RequiredArgsConstructor
public class PrinterControlService implements PrinterControl {

    private final PrinterReactorManager manager;

    /** Held here rather than reached through {@link PrinterReactor} because a transfer outlives the request that started it and must be findable again to be stopped - state about work in progress, not about a printer. */
    private final SdUploader uploads;

    /** Opened off the caller's thread: {@code openReactor} is {@code synchronized}, and a monitor is still not something an event-loop thread should queue on for several requests to one printer. Deferred, so a request cancelled before subscription opens nothing at all. */
    private Mono<PrinterReactor> reactor(UUID id) {
        return Mono.fromSupplier(() -> manager.openReactor(id)).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<HeaterReading> state(UUID id) {
        return reactor(id).flatMap(PrinterReactor::state);
    }

    @Override
    public Flux<EventMessage> updates(UUID id) {
        return reactor(id).flatMapMany(PrinterReactor::updates);
    }

    @Override
    public Flux<EventMessage> traffic(UUID id) {
        return reactor(id).flatMapMany(PrinterReactor::traffic);
    }

    @Override
    public Mono<Void> send(UUID id, List<GRq<?>> commands) {
        return reactor(id).flatMap(r -> r.handle(commands));
    }

    /**
     * Three of the five gates a typed line passes (the other two are {@code rawBlock}'s), in the
     * order a refusal is cheapest to be honest about: the machine, then the line's characters and
     * length, then the four commands the transport owns, then the link.
     *
     * <p><strong>What is deliberately not checked is whether the line is a command any firmware
     * has</strong>, and that is the feature rather than a gap. Measured against MarlinSim
     * 2026-11-18: {@code M851} answers {@code echo:Unknown command: "M851"} then {@code ok P15 B3},
     * and {@code NOTGCODE} draws no reply at all. The machine's own refusal is the right answer from
     * the only party that knows what it was compiled with, and a console that second-guessed it
     * could not be used to explore a printer, which is what a console is for.
     *
     * <p>{@link PrinterReactorManager#openReactorIfKnown} rather than {@code openReactor}, so an
     * unknown machine is an {@code ABSENT} outcome rather than an {@code IllegalArgumentException}
     * the web layer would have to classify - and on {@code boundedElastic}, because that method is
     * {@code synchronized} and builds a resource.
     *
     * <p><strong>{@code Accepted} means the line is in the send queue, not that it has been
     * written.</strong> A line typed while an SD upload holds the queue exclusively still waits for
     * the whole transfer before it goes out - a {@code REGULAR} source does not preempt a
     * {@code REALTIME} one, which is the behaviour wanted on the wire (§6.3) - but it does not hold
     * an HTTP request open while it waits. Waiting would be an unbounded hang rather than a slow
     * answer: nothing on this path has a timeout, so a machine that has stopped acknowledging stalls
     * the window and the {@code POST} never completes, which leaves an operator unable to tell a
     * refused line from a wedged machine. The useful signal is already better and already elsewhere -
     * {@code traffic()} carries the framed {@code SENT} line the instant it goes out, whether or not
     * anyone kept a request open - it is the precedent {@link #uploadToCard} set one endpoint over,
     * and it keeps this route from being stricter than the aggregate's, which acknowledges a command
     * once the aggregate has applied it and never waits for the wire.
     */
    @Override
    public Mono<ControlOutcome> sendRaw(UUID id, String line) {
        return Mono.fromSupplier(() -> manager.openReactorIfKnown(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(found -> found
                        .map(reactor -> type(reactor, id, line))
                        .orElseGet(() -> Mono.just(ControlOutcome.absent(id,
                                "the farm has no machine " + id))));
    }

    private Mono<ControlOutcome> type(PrinterReactor reactor, UUID id, String line) {
        String block;
        try {
            // code/dsl throws at the call site, and this is that call site: catching it here is the
            // layer boundary where a throw becomes a value. The message names the character and its
            // position and client.ts shows it to whoever typed the line, so it is passed through
            // verbatim rather than reworded. A null body reaches rawBlock as the blank line it is,
            // which it already has the right sentence for.
            block = rawBlock(line == null ? "" : line);
        } catch (IllegalArgumentException malformed) {
            return Mono.just(ControlOutcome.malformed(id, malformed.getMessage()));
        }
        var hostsOwn = whyTheHostOwnsIt(block);
        if (hostsOwn != null) {
            return Mono.just(ControlOutcome.malformed(id, hostsOwn));
        }
        // Two readings of one question, short-circuited into one answer: linked() is the aggregate's
        // view and the stated gate, and sendRaw answering false is the link having gone in the window
        // after it - the staleness PrinterReactor.linked() says it does not cover. One sentence for
        // both, so this route answers 409 for a race the other queue-feeding methods answer 500 for.
        // rawBlock answers the line trimmed, and the trimmed value is what goes out; sendRaw returns
        // once the queue has taken it, deliberately not once the window has written it.
        if (!reactor.linked() || !reactor.sendRaw(block)) {
            return Mono.just(ControlOutcome.wrongState(id,
                    "printer " + id + " is not connected, so there is nothing to type at"));
        }
        return Mono.just(ControlOutcome.accepted(id));
    }

    /**
     * Why this line is the host's to send and not an operator's to type, or null when it is theirs.
     *
     * <p>The three {@code rawBlock} deliberately leaves here, because they are host
     * <em>transport</em> policy rather than protocol syntax and {@code :gcode} must not know what
     * sender its caller has. The reasons are {@code PrinterState.onCommand}'s own, near-verbatim:
     * the same facts stated differently in two places is how two answers to one question start.
     */
    private static String whyTheHostOwnsIt(String block) {
        var head = head(block);
        if (isLineNumber(head)) {
            return "the line counter belongs to the send window, which adds the N field when it "
                    + "writes the block; a typed N would be framed behind the window's own as "
                    + "'N12 " + head + " ...', and spec 9 makes N not being first a structural error";
        }
        if (head.equalsIgnoreCase("M110")) {
            return "the line counter belongs to the send window; an M110 sent as a command moves the "
                    + "firmware's and not the host's, and every block after it is rejected for the "
                    + "rest of the session";
        }
        if (head.equalsIgnoreCase("M28")) {
            return "M28 turns the wire into a file sink until M29, so every command typed after it "
                    + "would be written into the file instead of run - and this route takes no "
                    + "exclusive hold of the queue. Use the upload endpoint, which holds the connection";
        }
        if (head.equalsIgnoreCase("M29")) {
            return "M29 ends a transfer this route never began";
        }
        if (head.equalsIgnoreCase("M112")) {
            return "an emergency stop queued behind a print is not one; M112 needs "
                    + "Priority.EMERGENCY, and this route is not that";
        }
        return null;
    }

    /**
     * The first whitespace-delimited token. Exact rather than a prefix match, which is what makes
     * {@code m110} and {@code M110 N0} refused and {@code M1100} sent; no regex, because one here
     * would be a third way of saying what two {@code indexOf} calls say.
     */
    private static String head(String block) {
        for (var i = 0; i < block.length(); i++) {
            var c = block.charAt(i);
            if (c == ' ' || c == '\t') {
                return block.substring(0, i);
            }
        }
        return block;
    }

    /** An {@code N} word, spec 9: the letter and at least one digit - so {@code NOTGCODE} is not one. */
    private static boolean isLineNumber(String token) {
        if (token.length() < 2 || (token.charAt(0) != 'N' && token.charAt(0) != 'n')) {
            return false;
        }
        for (var i = 1; i < token.length(); i++) {
            var c = token.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The reactor is opened first (with {@link PrinterReactorManager#openReactorIfKnown}, so an
     * unknown machine is an {@code ABSENT} outcome rather than an {@code IllegalArgumentException}
     * the web layer would have to classify), and handed to the uploader rather than looked up by it
     * - so "not connected" is a statement about this machine, not about the farm's bookkeeping, the
     * way the old {@code runtimeOf(id) == null} branch read.
     */
    @Override
    public Mono<ControlOutcome> uploadToCard(UUID id, UUID fileId, String cardName, boolean binary) {
        return Mono.fromSupplier(() -> manager.openReactorIfKnown(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(found -> found
                        .map(reactor -> uploads.start(reactor, id, fileId, cardName, binary))
                        .orElseGet(() -> Mono.just(ControlOutcome.absent(id,
                                "the farm has no machine " + id))));
    }

    /**
     * Stops a transfer, opening nothing to do it: {@link PrinterReactorManager#knows}, not
     * {@code openReactorIfKnown}, since building a runtime (a socket's worth of resource, two
     * threads since #54) just to say "there is no such machine" would be wasted on a transfer that
     * is not running - the uploader's own map needs no reactor to be looked in. Nothing to stop
     * stays {@code Accepted}; the page's button and the machine's state are different clocks.
     */
    @Override
    public Mono<ControlOutcome> cancelCardUpload(UUID id) {
        return Mono.defer(() -> manager.knows(id)
                ? uploads.cancel(id).thenReturn(ControlOutcome.accepted(id))
                : Mono.just(ControlOutcome.absent(id, "the farm has no machine " + id)));
    }

    @Override
    public Mono<Void> connect(UUID id, ConnectCmd command) {
        return reactor(id).flatMap(r -> r.handleConnectCmd(Mono.just(command)));
    }

    @Override
    public Mono<ConnectionStatus> connection(UUID id) {
        return reactor(id).flatMap(PrinterReactor::connection);
    }
}
