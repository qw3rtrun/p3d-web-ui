package org.qw3rtrun.p3d.terminal;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.msg.BlockAcknowledged;
import org.qw3rtrun.p3d.api.msg.BlocksAbandoned;
import org.qw3rtrun.p3d.api.msg.TerminalTraffic;
import org.qw3rtrun.p3d.g.code.core.session.GSendWindow;
import org.qw3rtrun.p3d.g.marlin.binary.MbpRs;
import org.qw3rtrun.p3d.g.marlin.binary.MbpRsDecoder;
import org.qw3rtrun.p3d.g.marlin.event.CapabilitiesReport;
import org.qw3rtrun.p3d.g.marlin.event.CapabilitiesReportReader;
import org.qw3rtrun.p3d.g.marlin.event.EndstopReport;
import org.qw3rtrun.p3d.g.marlin.event.EndstopReportReader;
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.g.marlin.event.SdFileListBegin;
import org.qw3rtrun.p3d.g.marlin.event.SdFileListEnd;
import org.qw3rtrun.p3d.g.marlin.event.SdFileListReader;
import org.qw3rtrun.p3d.g.marlin.event.SettingsReport;
import org.qw3rtrun.p3d.g.marlin.event.SettingsReportReader;
import org.qw3rtrun.p3d.g.protocol.AdvancedOkRs;
import org.qw3rtrun.p3d.g.protocol.BusyRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
import org.qw3rtrun.p3d.g.protocol.OkRs;
import org.qw3rtrun.p3d.g.protocol.ResendRs;
import org.qw3rtrun.p3d.g.protocol.SimpleOkRs;
import org.qw3rtrun.p3d.g.protocol.WaitRs;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.Math.min;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.qw3rtrun.p3d.api.msg.TerminalTraffic.Direction.RECEIVED;
import static org.qw3rtrun.p3d.api.msg.TerminalTraffic.Direction.SENT;

/**
 * One terminal session, two coupled pipelines. {@link CommandProcessor} pulls G-code from a source,
 * numbers and frames it through {@code :gcode}'s {@link GSendWindow}, and writes it bounded by the
 * printer's own buffer; {@link ReplyFlux} frames reply lines, decodes each once via
 * {@code MarlinRsDecoder} and folds the three answers that span several lines into one reply each
 * ({@link ReplyFlux#accumulate}), and {@link ReplaySubscriber} routes the result back into the send
 * window or onto {@link #messageFlux()}. The coupling is real: an {@code ok} or {@code busy:}
 * arriving on the inbound side is half of outbound flow control.
 *
 * <p>A reply that is one line is published as itself; the two answers that are a <em>block</em> of
 * lines are folded into one value before they leave - {@code M20}'s listing into one
 * {@link SdFileList}, and {@code M115}'s 38 {@code Cap:} lines into one {@link CapabilitiesReport}
 * (see {@link ReplyFlux#accumulate}), so that one question answered by the firmware is one value on
 * the bus rather than a burst a subscriber has to re-assemble.
 *
 * <p>Three threads matter - the producer pushing G-code, the netty inbound loop delivering replies,
 * and whichever of those two first enters a drain loop and so does the writing or emitting for both.
 * Every counter either side shares is a {@link Requested} or guarded by an
 * {@link AtomicInteger}-driven drain loop (see {@link CommandProcessor} and {@link #drainBus()}),
 * never a plain field touched from two threads.
 *
 * <p>{@link #messageFlux()} ends only when the connection does; a firmware error is a value on it
 * (see {@link ReplaySubscriber#onNext}). {@link #stop()} cancels both halves and disposes the
 * connection, because cancelling alone does not close the socket.
 *
 * <p><strong>There is a third thing on the wire.</strong> {@link #binaryTransfer} drives Marlin's
 * Binary Protocol over the same socket: packets out through {@link CommandProcessor#writeRaw} and
 * replies in through the same {@link ReplyFlux}, which decodes them with {@code MbpRsDecoder} while
 * {@link #binaryMode} is on. It is a third drain loop ({@link MbpTransferDriver}) and not a third
 * pipeline - the outbound carries {@code byte[]} for exactly this reason.
 *
 * <p><strong>And there is a second thing a caller can watch.</strong>
 * {@link #observeTraffic(Consumer)} reports every line that crossed the wire, either way, as a
 * {@link TerminalTraffic} - the raw text with the decode as an <em>annotation</em>, which is what a
 * per-machine console shows. A {@code volatile} consumer and not a second {@code Flux}: the sink has
 * to outlive a redial (a new {@code HostTerminal} is built on every reconnect) so
 * {@code PrinterReactor} owns it, and this class's subscriber-facing surface stays one flux. Three
 * sites report, all of them through the one {@link #outbox} and {@link #drainBus()} so that no new
 * concurrency appears here: {@link CommandProcessor#write0} (the framed block,
 * {@code decodedAs} null), {@link CommandProcessor#writeRaw0} ({@link #hex} of the packet, for the
 * same reason the traffic log shows it that way), and {@link ReplyFlux#decode} - for
 * <em>every</em> received line, <strong>before</strong> the early return that drops one no decoder
 * claimed.
 *
 * <p>That last one is the reason the seam exists. Censused by {@code TerminalConsoleProbe} against
 * MarlinSim (Marlin bugfix-2.1.x) on {@code localhost:8099} on 2026-11-18, 106 received lines:
 * <pre>
 *   UNCLAIMED - nothing reaches a subscriber    10   the whole boot banner; decode() drops these
 *   RENDERABLE from the decoded event           81
 *   LOSSY - encode() is not the line             3
 *   CONSUMED by the transport as flow control   12   every `ok P15 B3`
 * </pre>
 * 25 of 106 cannot be rendered from a decoded reply at all, and the outbound direction has no
 * subscriber-visible form whatsoever - so a console cannot be a filtered view of
 * {@link #messageFlux()}, and this is not a duplicate of it.
 */
@Slf4j
public class HostTerminal {

    private static final Logger TRAFFIC_LOGGER = LoggerFactory.getLogger(HostTerminal.class.getName() + "#traffic");

    /** A packet as the traffic log shows it, since a packet has no printable form of its own. */
    static String hex(byte[] bytes) {
        var text = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            text.append(text.isEmpty() ? "" : " ").append(format("%02X", b));
        }
        return text.toString();
    }

    private final Supplier<Flux<String>> inbound;

    /**
     * Where a framed block goes. <strong>Bytes, not text</strong> - a Marlin Binary Protocol packet
     * starts {@code AD B5}, and the {@code sendString} this used to be built on encodes UTF-8, which
     * turns every byte above {@code 0x7F} into two. Carrying packets as {@code String}s abused as
     * byte holders would be an invariant no compiler and no {@code trim()} respects, so the one
     * writer in this class hands over {@code byte[]} and encodes its own ASCII (see
     * {@link CommandProcessor#write0}).
     */
    private final Function<Publisher<byte[]>, Mono<Void>> outbound;

    /**
     * How to close the socket, since cancelling the two halves does not. Needed once {@code cancel()}
     * stopped faking a completion to end the connection (completing the outbound used to be what
     * ended {@code reactor-netty}'s send) - observed on a live disconnect: it reported
     * {@code OFFLINE}, sent its {@code M155 S0}, and left the socket {@code Established} on the
     * printer. The no-arg constructor defaults to doing nothing, which is right for a terminal built
     * over a {@code Sinks} in tests - there is nothing to close.
     *
     * <p><strong>It owes one guarantee: it may not discard a block already written.</strong>
     * {@link CommandProcessor#write0} returning means the bytes were handed to the outbound, which
     * in {@code reactor-netty} is two event-loop tasks short of the socket - so a close that merely
     * calls {@code Connection.dispose()} drops a goodbye that this class believes it sent, in
     * silence and with no error anywhere. {@link TerminalManager#closeAfterFlush} is how the real
     * one keeps it, and carries the measurements.
     */
    private final Runnable disposeConnection;

    public HostTerminal(Supplier<Flux<String>> inbound,
                        Function<Publisher<byte[]>, Mono<Void>> outbound) {
        this(inbound, outbound, () -> {
        });
    }

    public HostTerminal(Supplier<Flux<String>> inbound,
                        Function<Publisher<byte[]>, Mono<Void>> outbound,
                        Runnable disposeConnection) {
        this.inbound = inbound;
        this.outbound = outbound;
        this.disposeConnection = disposeConnection;
    }

    /**
     * How many replies the bus holds for a subscriber that has not attached yet. Was 1
     * ({@code replay().latest()}): the opening exchange of a session is 7 lines
     * ({@code M115}'s {@code FIRMWARE_NAME:}, five {@code Cap:} lines, {@code ok}), and
     * {@link #start} begins reading the socket before a caller can possibly subscribe
     * {@link #messageFlux()} - measured, six replies emitted with nothing attached arrived as one.
     * 256 is {@code publishOn}'s own prefetch and 25x the opening exchange; what matters is that it
     * is not 1, and a subscriber arriving later than that still misses the oldest.
     */
    static final int BACKLOG = 256;

    /**
     * The reply bus. Was {@code Sinks.unsafe()}, which promises its own serialisation - true while
     * every emission came from the netty inbound thread, until {@link BlockAcknowledged} started
     * emitting from inside the send window's drain loop, on whichever thread got there first. The
     * safe sink that replaced it only reports the collision as {@code FAIL_NON_SERIALIZED} rather
     * than corrupting state, and {@link #emit} used to log that and drop the reply - measured, two
     * threads hammering a safe sink: <strong>26,640 of 40,000 emissions refused</strong>. The actual
     * fix is {@link #drainBus()}: it serialises the emitters so the sink never sees two threads.
     */
    private final Sinks.Many<Object> replays = Sinks.many().replay().limit(BACKLOG);

    /** Replies decided on but not yet on the bus, oldest first - what makes {@link #drainBus()}
     * possible: a thread that finds another one already emitting parks here and leaves. */
    private final Queue<Object> outbox = new ConcurrentLinkedQueue<>();

    /** The emission guard: zero when no thread is in {@link #drainBus()}, otherwise the number of
     * calls still to be accounted for. */
    private final AtomicInteger emitting = new AtomicInteger();

    /** Set once a terminal signal has gone out; read only inside {@link #drainBus()}, so a plain
     * field is safe - {@link #emitting}'s hand-off between draining threads carries the
     * happens-before this needs, the same way {@code GSendWindow}'s plain fields are safe. */
    private boolean busEnded;

    /**
     * The end of the bus, parked in the outbox so it cannot overtake a reply queued in front of it -
     * emitting it straight to the sink from the inbound thread could terminate the sink under a
     * still-pending acknowledgement, which the drain loop would then see refused with
     * {@code FAIL_TERMINATED}.
     *
     * @param error what killed the inbound side, or null when it merely ended
     */
    private record BusEnd(Throwable error) {
    }

    /**
     * One line of wire traffic, parked in the same outbox so that one drain loop serialises it with
     * the replies around it. A wrapper rather than the bare record because {@link #publish} routes
     * on type and a {@link TerminalTraffic} goes to the observer, never to the reply bus.
     */
    private record Traffic(TerminalTraffic line) {
    }

    /**
     * Where a console's lines go, defaulting to a no-op so an unobserved terminal costs nothing.
     * {@code volatile} because {@code PrinterReactor.onConnected} sets it on the aggregate's thread
     * before {@link #start} is subscribed, and it is then read from the netty inbound loop and from
     * whichever thread is in {@link #drainBus()} - a reference write is atomic and a snapshot is all a
     * report needs, the same argument {@link #binaryMode} and {@link #binaryDriver} make.
     */
    private volatile Consumer<TerminalTraffic> trafficObserver = line -> {
    };

    /**
     * Watches the wire: every line this session writes and every line it reads, raw, with the decoded
     * reply's simple class name as an annotation where there is one. A console wants the bytes - 25
     * of 106 measured received lines cannot be rendered from a decoded reply, and an outbound block
     * has no decoded form at all; see this class's javadoc for the census.
     *
     * <p>One observer, replaced wholesale and settable at any time - it may be called from any of the
     * threads this class uses, but only ever one at a time, since every report goes through
     * {@link #drainBus()}. It must not block or throw: it is on the path a block takes to the wire, and
     * a throw is caught and logged rather than allowed to wedge the loop.
     */
    public void observeTraffic(Consumer<TerminalTraffic> observer) {
        this.trafficObserver = Objects.requireNonNull(observer, "observer");
    }

    /** Parks one line of traffic for the drain loop, the same hand-off {@link #emit} makes - a report
     * is raised from the netty inbound loop and from inside the send window's drain loop, neither of
     * which may call a stranger's consumer directly. */
    private void reportTraffic(TerminalTraffic line) {
        outbox.add(new Traffic(line));
        drainBus();
    }

    private CommandProcessor outSide;
    private ReplaySubscriber inSide;

    /**
     * Whether the printer is reading packets rather than ASCII, which decides <em>which decoder</em>
     * {@link ReplyFlux#decode} tries first. Written by {@link MbpTransferDriver} (through
     * {@link #enterBinaryMode}/{@link #leaveBinaryMode}), read on the netty inbound loop, so
     * {@code volatile} and nothing more - a boolean write is atomic and a snapshot is all a decode
     * needs.
     *
     * <p><strong>Why a mode flag at all:</strong> {@code rs<n>} belongs to both protocols.
     * {@code MarlinRsDecoder.decode("rs7")} answers {@code ResendRs(lineNumber = 7)} - a
     * base-protocol <em>line</em> resend - while in binary mode {@code rs7} is a <em>packet</em>
     * resend, and nothing in the text tells them apart: only the host knows which mode it put the
     * printer in. {@code :gcode} keeps the two registries apart for exactly this reason
     * ({@code MbpRsTest$NotInTheMainRegistry} pins it). Without the gate a packet resend reaches the
     * ASCII send window, matches nothing it holds, triggers {@link CommandProcessor#resynchronise},
     * restarts the line numbering and publishes a {@link BlocksAbandoned} - a healthy ASCII session
     * destroyed over a reply that was never about it.
     *
     * <p><strong>Set eagerly, cleared lazily</strong> - before {@code M28 B1}'s {@code ok} could
     * arrive, and only after the {@code CONTROL CLOSE}'s {@code ok}. Both are safe because
     * {@code MbpRsDecoder} claims {@code ok<digits>} only: measured,
     * {@code MbpOk.decode("ok P15 B3")} declines, since {@code digitsEnd} hits a blank right after
     * {@code ok}, and Marlin writes its numbered ASCII acknowledgement {@code ok N3 P15 B3} and
     * never {@code ok3}. So an ASCII {@code ok} crossing the boundary in either direction still
     * falls through to {@code MarlinRsDecoder} and still credits the ASCII window.
     */
    private volatile boolean binaryMode;

    /** The transfer in progress, or null. One at a time: the protocol's window is one packet deep
     * and the caller holds the wire exclusively while it runs. */
    private volatile MbpTransferDriver binaryDriver;

    void enterBinaryMode(MbpTransferDriver driver) {
        binaryDriver = driver;
        binaryMode = true;
        log.info("IN: reading replies as binary-protocol replies first");
    }

    void leaveBinaryMode(MbpTransferDriver driver) {
        if (binaryDriver != driver) {
            return;
        }
        binaryMode = false;
        binaryDriver = null;
        log.info("IN: back to reading replies as the ASCII protocol's");
    }

    /**
     * Writes one file to the printer's card over Marlin's Binary Protocol, reporting progress as the
     * cumulative count of payload bytes the firmware has acknowledged - one {@code Long} per
     * acknowledged write block, which jumps to the file's full size on the last block so that 100%
     * is exact. Not a percentage, not a delta. An empty file reports nothing and completes.
     *
     * <p><strong>Cold.</strong> Nothing happens until subscribe: no mode change, no packet.
     *
     * <p><strong>Two things the caller owes, and neither can be arranged from here.</strong>
     * <ol>
     * <li><strong>Exclusive hold of the wire</strong> -
     * {@code PublisherQueue.addExclusivePublisher(Priority.REALTIME, ...)}, whose publisher emits
     * nothing more while this flux runs. An ASCII line injected mid-packet lands inside a packet the
     * firmware is already reading a header for, and corrupts it. No mode gate is added to
     * {@link CommandProcessor#canProcess()} for this: the queue already provides the exclusion, and
     * a second mechanism would be two half-mechanisms. (The transient {@code rawPackets} term in
     * {@code canProcess()} is a different thing and stays.)</li>
     * <li><strong>The {@code M28 B1} itself</strong>, as an ordinary ASCII block through that same
     * exclusive publisher. It is a numbered block and its {@code ok N4 P15 B3} is ASCII window
     * credit like any other; this flux does not write it, because an unnumbered copy poked at the
     * wire from here would be credit the send window never counted.</li>
     * </ol>
     *
     * <p>The two together are one source that emits {@code M28 B1} and then stays alive emitting
     * nothing - a {@code Mono.just("M28 B1")} would <em>complete</em>, release the exclusivity and
     * let another source write an ASCII line into the middle of a packet. {@code MbpTransferProbe}
     * runs exactly this against a printer:
     *
     * <pre>
     * var transferred = Sinks.&lt;Void&gt;empty();
     * queue.addExclusivePublisher(Priority.REALTIME,
     *         Flux.concat(Mono.just("M28 B1"), Flux.&lt;String&gt;never())
     *                 .takeUntilOther(transferred.asMono()));
     * terminal.binaryTransfer(name, data, cancel).doFinally(s -&gt; transferred.tryEmitEmpty())
     * </pre>
     *
     * <p>On subscribe the mode flag goes on (see {@link #binaryMode}) and then the driver
     * <strong>waits for {@code echo:Switching to Binary Protocol}</strong> before writing a single
     * packet. That wait is not optional: Marlin reads serial into a command queue and executes
     * {@code M28 B1} later, out of that queue, so bytes written in between are consumed by the
     * <em>ASCII</em> reader and become garbage commands. The echo is written from the execution
     * stage, which makes it the one signal the mode really changed - and measured, it arrives
     * <em>before</em> the {@code ok}, which is why waiting on it beats waiting on the {@code ok}. It
     * is timed, so an incapable printer fails rather than hangs.
     *
     * <p><strong>A cancellation completes, it does not error.</strong> {@code cancel} firing drives
     * {@code MbpTransfer.abort}, which still leaves binary mode through a {@code CONTROL CLOSE} and
     * then completes - the caller tells cancelled from failed with a flag it set before firing
     * {@code cancel}, exactly as the ASCII path does, and an {@code onError} would show the operator
     * a failure when they asked for a stop. Any other give-up is an
     * {@link MbpTransferDriver.Failed} carrying {@code MbpTransfer.reason} verbatim.
     *
     * <p>The measured session, the two chosen numbers and the thread discipline are all in
     * {@link MbpTransferDriver}'s javadoc.
     *
     * @param cardName the file to create on the card
     * @param data     the whole file; sliced into blocks of the buffer size the firmware reports
     * @param cancel   fires to stop the transfer; {@code Mono.never()} for one that cannot be stopped
     * @return the acknowledged byte counts, completing when the file is closed on the card
     */
    public Flux<Long> binaryTransfer(String cardName, byte[] data, Mono<Void> cancel) {
        return binaryTransfer(cardName, data, cancel, Schedulers.parallel(),
                MbpTransferDriver.REPLY_TIMEOUT, MbpTransferDriver.ATTEMPTS);
    }

    /** The same, with the retry clock handed in - what lets a test drive the timeout on a
     * {@code VirtualTimeScheduler} instead of waiting two real seconds. */
    Flux<Long> binaryTransfer(String cardName, byte[] data, Mono<Void> cancel,
                              Scheduler scheduler, Duration timeout, int attempts) {
        return Flux.defer(() -> {
            CommandProcessor out = outSide;
            if (out == null) {
                return Flux.<Long>error(new IllegalStateException(
                        "start() has not been called, so there is nowhere to write a packet"));
            }
            return new MbpTransferDriver(this, out::writeRaw, cardName, data,
                    scheduler, timeout, attempts).run(cancel);
        });
    }

    /**
     * Opens the session: the socket is read from this call, the writing waits for a subscriber. The
     * asymmetry is deliberate - subscribing the inbound side here, not inside the returned
     * {@code Mono}, means a reply can arrive before the caller subscribes {@link #messageFlux()} (it
     * cannot subscribe any earlier, since this call is what creates both halves); the backlog that
     * covers the gap is described there.
     */
    public Mono<Void> start(Publisher<String> gcodes) {
        this.inSide = new ReplaySubscriber();
        this.outSide = new CommandProcessor(gcodes);
        new ReplyFlux(inbound.get(), () -> binaryMode, this::reportTraffic)
                .subscribe(inSide);
        return outbound.apply(outSide);
    }

    /** Ends the session: both halves cancelled, and the socket actually closed - the third step is
     * the one that is easy to leave out, and the one that must not overtake a block already
     * written; see {@link #disposeConnection}. */
    public void stop() {
        outSide.cancel();
        inSide.handleCancel();
        disposeConnection.run();
    }

    /**
     * The replies worth telling a subscriber about, already decoded - typed {@code GRs} values, not
     * text, decoded once by {@code :gcode}'s {@code MarlinRsDecoder} and propagated as-is. (A second
     * model used to sit on this boundary, {@code ReplyEvents} turning each {@code GRs} into an
     * {@code api.msg} record - nullable {@code BigDecimal} into zero-filled {@code double} - and it
     * is gone: this farm drives Marlin, so what Marlin said is what a subscriber gets.)
     *
     * <p>What drives the protocol is consumed rather than published: a bare/advanced {@code ok},
     * {@code wait}, {@code Resend}, and every {@link MbpRs} - a packet-stream reply belongs to
     * {@link MbpTransferDriver}, and an {@code MbpOk} published as a reply would read as an
     * acknowledgement of something it has nothing to do with. A line no decoder claims is dropped
     * with a {@code #traffic} debug line.
     *
     * <p><strong>A multi-line answer arrives as one value.</strong> {@code M20}'s listing is one
     * {@link SdFileList} ({@link ReplyFlux#listing}); {@code M119}'s endstop report, {@code M503}'s
     * settings dump and {@code M115}'s capability block are each one {@link EndstopReport},
     * {@link SettingsReport} or {@link CapabilitiesReport} ({@link ReplyFlux#accumulate}) - one event
     * per read, never one per line, and no report at all from a firmware that sent nothing to fold.
     *
     * <p>A transfer's ordinary traffic still arrives here: measured mid-transfer,
     * {@code echo:SD card ok}, {@code echo:Now fresh file:}, {@code Writing to file:},
     * {@code //action:notification} and {@code echo:SD card released} all fall through the binary
     * decoder to {@code MarlinRsDecoder} and onto this bus.
     *
     * <p>Two values on this flux are not replies at all, both facts about the send window a
     * subscriber cannot otherwise see: {@link BlockAcknowledged} for an {@code ok} that released a
     * block (the only honest progress signal there is), and {@link BlocksAbandoned} for a
     * {@code Resend:} the window could not answer (see
     * {@link CommandProcessor#resynchronise}) - emitted only when a block really was dropped.
     *
     * <p>This flux terminates when the connection does, and at no other time - a printer fault is an
     * {@code ErrorRs} value like any other reply (see {@link ReplaySubscriber#onNext}), not an
     * {@code onError} here.
     *
     * <p><strong>It does not matter when this is subscribed, or which thread decided a reply.</strong>
     * {@link #start} begins reading the socket before any caller can possibly subscribe, so the bus
     * holds a backlog of {@link #BACKLOG} replies for a subscriber not yet attached, and every
     * emission goes through one drain loop rather than straight at the sink - every reply reaches the
     * first subscriber, in order, whenever it attaches, and a second subscriber gets the same backlog
     * then the live stream.
     */
    public Flux<Object> messageFlux() {
        return replays.asFlux();
    }

    public void onOk(OkRs<?> ok) {
        outSide.handleBackpressure(ok);
        publishPayload(ok);
    }

    /**
     * An {@code ok} is an acknowledgement, and sometimes also a reply - {@code M105}/{@code M109}/
     * {@code M190} answer on the {@code ok} itself (e.g. {@code ok T:45.26 /45.00 B:25.00 /0.00}), so
     * dropping every {@code ok} as pure window credit used to answer an on-demand {@code M105} into
     * the void. Only {@link SimpleOkRs} (nothing to decode) and {@link AdvancedOkRs} (window credit
     * only, e.g. {@code ok N2 P15 B3}) are held back; everything else goes out whole, with the
     * {@code ok} surviving as a type rather than a string prefix - {@code OkTemperatureRs} vs.
     * {@code BareTemperatureRs} is exactly that distinction.
     */
    private void publishPayload(OkRs<?> ok) {
        if (ok instanceof SimpleOkRs || ok instanceof AdvancedOkRs) {
            return;
        }
        emit(ok);
    }

    /**
     * The machine cannot take commands, so nothing in flight will be acknowledged. Matches
     * {@link BusyRs} rather than a concrete class so both spellings fire: the RepRap bare
     * {@code busy: processing} ({@code SimpleBusyRs}) and Marlin's echoed
     * {@code echo:busy: processing} ({@code BusyNotice}) - the parser this replaced claimed only the
     * bare form, so on a real Marlin the send window was never told the printer had stalled.
     */
    void onBusy(BusyRs<?> busy) {
        outSide.clearInFlight();
        emit(busy);
    }

    /**
     * {@code Resend: n} (spec 8.5 step 5): the firmware rejected a numbered block and discards
     * everything after it until it sees {@code n} again. {@link CommandProcessor#rewind} replays
     * block {@code n} and everything sent after it, byte for byte. Not published - like an advanced
     * {@code ok}, it is protocol traffic the transport owns; a subscriber sees the {@code Error:}
     * that precedes it instead.
     */
    void onResend(int lineNumber) {
        outSide.rewind(lineNumber);
    }

    void onWait() {
        // TODO Unsupported yet
    }

    /**
     * Puts a reply on the bus - parked first, so two threads never emit at once. The two that reach
     * here are the netty inbound loop (a decoded reply) and whichever thread's send-window drain
     * loop released a {@link BlockAcknowledged}; calling {@code tryEmitNext} directly from either and
     * logging a {@code FAIL_NON_SERIALIZED} collision, as before, drops the reply after the fact
     * instead of preventing the collision.
     */
    private void emit(Object reply) {
        outbox.add(reply);
        drainBus();
    }

    /**
     * Ends the bus - reserved for the transport dying. A fault the <em>printer</em> reported is a
     * value through {@link #emit} instead; routing it here used to terminate the sink (and every
     * subscriber of {@link #messageFlux()}) over a checksum the printer itself recovered from on the
     * next line.
     */
    private void endBus(Throwable error) {
        outbox.add(new BusEnd(error));
        drainBus();
    }

    /**
     * Publishes what is parked, one thread at a time - atomics plus a drain loop, the same shape as
     * {@link CommandProcessor#processRequest()} and {@link PublisherQueue}. The first caller in runs
     * the loop; one that arrives while it is running leaves its reply in the outbox and returns, and
     * the thread inside goes round again to pick it up - nothing blocks or busy-waits, unlike
     * {@code emitNext(reply, EmitFailureHandler.busyLooping(...))}. Because this is the only path to
     * the sink, {@code FAIL_NON_SERIALIZED} is now unreachable there.
     */
    private void drainBus() {
        if (emitting.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        for (; ; ) {
            for (Object parked; (parked = outbox.poll()) != null; ) {
                publish(parked);
            }
            missed = emitting.addAndGet(-missed);
            if (missed == 0) {
                return;
            }
        }
    }

    /** One parked item onto the sink. Drain loop only, so {@link #busEnded} is plain. */
    private void publish(Object parked) {
        if (parked instanceof Traffic report) {
            // Routed ahead of the busEnded check and not gated on it: the reply bus ending is the
            // inbound half dying, and the console's stream is not the reply bus - silencing it there
            // would lose the last lines of a session, which are the ones an operator is looking at.
            try {
                trafficObserver.accept(report.line());
            } catch (RuntimeException broken) {
                log.warn("a traffic observer threw on {}; the drain loop carries on", report.line(), broken);
            }
            return;
        }
        if (parked instanceof BusEnd end) {
            if (busEnded) {
                return;
            }
            busEnded = true;
            Sinks.EmitResult result = end.error() == null
                    ? replays.tryEmitComplete()
                    : replays.tryEmitError(end.error());
            if (result.isFailure()) {
                log.warn("IN: the bus did not terminate ({}): {}", result, end.error());
            }
            return;
        }
        if (busEnded) {
            // Nothing follows a terminal signal, ever. A late acknowledgement from the send window
            // is the normal way to get here and is not worth a warning.
            log.debug("IN: the bus has ended, so this reply goes nowhere: {}", parked);
            return;
        }
        Sinks.EmitResult result = replays.tryEmitNext(parked);
        if (result.isFailure()) {
            log.warn("IN: reply dropped ({}): {}", result, parked);
        }
    }

    /**
     * Frames reply lines off the wire and decodes each once - through {@code MbpRsDecoder} then
     * {@code MarlinRsDecoder} while binary mode is on, through {@code MarlinRsDecoder} alone
     * otherwise - and then folds the replies that are really one answer into one value. Built once
     * per session, in {@link #start}, on the netty inbound loop, which is the only thread that ever
     * touches it or the four readers it holds.
     *
     * <p><strong>Four multi-line answers are assembled here, by four {@code :gcode} readers.</strong>
     * {@link #listing} has a terminator line to cut on ({@code End file list}); {@link #endstops},
     * {@link #settings} and {@link #capabilities} do not, so each is scoped to <em>membership of its
     * own family</em> instead, and {@link #accumulate} ends a burst at the first reply that is not a
     * member of the one currently open - in practice the {@code ok}. See {@link #accumulate} for why
     * that scoping is safe on this wire, why the three can share one fold stage, and what it costs on
     * a dead socket.
     */
    public static class ReplyFlux extends FluxOperator<String, GRs<?>> {

        private final Flux<GRs<?>> flux;

        /** Whether to try the packet protocol's decoder first - see {@link HostTerminal#binaryMode}.
         * A supplier and not a copy: the mode changes under a live subscription. */
        private final BooleanSupplier binaryMode;

        /** Where a received line is reported for a console - see
         * {@link HostTerminal#observeTraffic(Consumer)}. Handed in because this class is static and
         * cannot reach {@link HostTerminal#drainBus()}, and a callback rather than the observer
         * itself for the same reason {@link #binaryMode} is a supplier: the observer changes under a
         * live subscription. */
        private final Consumer<TerminalTraffic> traffic;

        /**
         * {@code M20}'s listing, being assembled. The oldest of the three readers this side holds,
         * and the only one that works on raw lines rather than decoded replies: a listing's entries
         * ({@code Begin file list}, entries, {@code End file list}) are deliberately not claimable
         * replies ({@code TEST.GCO 47} is too common a {@code <word> <number>} shape for a decoder to
         * own), so they reach {@link #decode} as lines no decoder wants and have nowhere else to be
         * collected.
         */
        private final SdFileListReader listing = new SdFileListReader();

        /** {@code M119}'s endstop report, being assembled - see {@link #accumulate}. */
        private final EndstopReportReader endstops = new EndstopReportReader();

        /** {@code M503}/{@code M92}'s settings burst, being assembled - see {@link #accumulate}. */
        private final SettingsReportReader settings = new SettingsReportReader();

        /**
         * {@code M115}'s capability block, being folded - see {@link #accumulate}. Scoped to "the
         * next reply that is a {@code CapabilityRs}", ended by the first one that is not, which
         * {@code CapabilitiesInterleaveProbe} measured against MarlinSim (Marlin bugfix-2.1.x) on
         * 2026-11-20 to be safe: the {@code FIRMWARE_NAME:} header, all 38 {@code Cap:} lines and the
         * closing {@code ok P15 B3} arrive at the same millisecond offset, with a 1-second
         * {@code M155 S1} temperature tick strictly outside the burst on both sides - 0ms in front of
         * it and the next at 651ms behind the {@code ok}. Nothing was observed landing inside.
         */
        private final CapabilitiesReportReader capabilities = new CapabilitiesReportReader();

        /**
         * The line being assembled across chunks - whatever the most recent chunk ended inside,
         * with no terminator in it. What makes {@link #frame} a stateful buffer rather than
         * {@code flatMap} over characters: touched only here, on the one thread that ever calls
         * {@link #frame} - the netty inbound loop - like {@link #listing} and the other readers
         * this class holds.
         */
        private final StringBuilder assembling = new StringBuilder();

        /**
         * Cuts one chunk of the wire into zero or more complete lines - the {@code \n} itself not
         * included - and holds back whatever did not end in one for the next call. Replaces a
         * {@code flatMap} that boxed every character into its own {@code String} and pushed it
         * through an inner {@code Flux}; that old shape could interleave two chunks' characters in
         * principle and was safe only by two accidents of the pipeline it sat in (see the note on
         * {@link #accumulate} for both). Neither accident is load-bearing for this method: it has
         * no inner publisher, so a chunk is sliced in the one call that received it and there is
         * nothing left to interleave, regardless of which thread calls it or how much demand is
         * outstanding downstream.
         *
         * <p>{@code concatMapIterable} is what turns the list this returns back into the individual
         * lines, in order - the same operator {@link #accumulate} already used for the same reason,
         * and the deliberate alternative to {@code flatMap}.
         *
         * @param chunk one {@code onNext} from the socket - a partial line, one whole line, or
         *              several
         * @return the lines {@code chunk} completed, oldest first; empty when it completed none
         */
        private List<String> frame(String chunk) {
            assembling.append(chunk);
            List<String> lines = null;
            int start = 0;
            for (int i = 0; i < assembling.length(); i++) {
                if (assembling.charAt(i) == '\n') {
                    if (lines == null) {
                        lines = new ArrayList<>();
                    }
                    lines.add(assembling.substring(start, i));
                    start = i + 1;
                }
            }
            if (start > 0) {
                assembling.delete(0, start);
            }
            return lines == null ? List.of() : lines;
        }

        // The firmware never sends a *packet* to the host - every reply in both modes is an ASCII
        // line - so the inbound .asString() needs no change and :gcode's MbpPacketReader has no
        // place in this pipeline.
        public ReplyFlux(Flux<String> source, BooleanSupplier binaryMode,
                         Consumer<TerminalTraffic> traffic) {
            super(source);
            this.binaryMode = binaryMode;
            this.traffic = traffic;
            this.flux = source
                    .concatMapIterable(this::frame)
                    .map(String::stripTrailing)
                    .doOnNext((in) -> TRAFFIC_LOGGER.debug("<- {}", in))
                    .handle(this::decode)
                    .concatMapIterable(this::accumulate);
        }

        /**
         * Folds {@code M119}'s, {@code M503}'s and {@code M115}'s multi-line answers into one reply
         * each, and passes everything else through. A member line is swallowed into its own reader;
         * the first reply that is <em>not</em> a member of whichever burst is open ends it, so one
         * input produces zero, one or two outputs: the completed report first and then the line that
         * ended it, which is almost always the {@code ok} and must still reach {@link ReplaySubscriber}
         * as window credit. A stream with no member line in it - a settings read with nothing changed
         * from default is not this case, but a firmware older than Marlin 1.1.9 answering {@code M115}
         * with the header alone is - never produces a report at all, because nothing was ever opened to
         * flush.
         *
         * <p><strong>Three readers, one {@code offer} each, and the order between them does not
         * matter.</strong> {@link EndstopReportReader#offer}, {@link SettingsReportReader#offer} and
         * {@link CapabilitiesReportReader#offer} each type-check the reply against its own family
         * ({@code EndstopRs<?>}; {@code SettingsSection}/{@code SettingsValue}; {@code CapabilityRs}) -
         * three disjoint {@code :gcode} types, since a decoded {@code GRs} value is exactly one
         * concrete type. So at most one {@code offer} below ever answers true for a given reply, and
         * checking them in a different order could only change which reader is asked first about a
         * reply none of them want - which still answers false from all three. The same holds for the
         * three {@code flush} calls once a burst closes: the probes below show the bursts never
         * interleave on the wire, but even if they did, each reader only ever holds what its own
         * {@code offer} accepted.
         *
         * <p><strong>Why {@code concatMapIterable} and not another {@code handle}.</strong>
         * {@code handle}'s {@link SynchronousSink} permits at most one emission per input, and the
         * line that ends a burst has two to make, in order: the assembled report, then the line itself
         * - almost always the {@code ok}, which must still reach the send window as the block of
         * credit it is, or the sender stalls by a block for the rest of the session. A second
         * {@code handle} stage answers a second {@code next} with an {@code IllegalStateException}.
         * {@code concatMapIterable} over a synchronous {@code List} is the smallest operator that
         * emits both, in order, and introduces no concurrency and no re-ordering of its own: the
         * {@code List} is built and drained inside the one {@code onNext} that produced it, so the
         * fold stays on the netty inbound loop that drives the upstream - which is what lets all three
         * readers be plain fields.
         *
         * <p><strong>Why "members of this family" and not "everything until the ok".</strong> The
         * generic rule is wrong on this wire: a {@code busy:} notice or a {@code Done printing file}
         * genuinely can land between an unrelated command and <em>its</em> {@code ok}, and would be
         * swallowed into a report it has nothing to do with. What makes the family-scoped rule safe is
         * that nothing lands <em>inside</em> any of these three answers - measured twice, independently:
         * {@code MultilineInterleaveProbe} on 2026-11-19 against MarlinSim on {@code localhost:8099},
         * with {@code M155 S1} ticking once a second throughout, found the tick before {@code M119}
         * arriving at 0ms, the report's four lines and its {@code ok P15 B3} all at 22ms, and the next
         * tick at 691ms ({@code M503} the same shape, a contiguous ~20-line burst with its {@code ok}
         * immediately behind the last line); {@code CapabilitiesInterleaveProbe} on 2026-11-20 found
         * {@code M115}'s header, all 38 {@code Cap:} lines and its {@code ok} at one millisecond
         * offset, with the temperature tick 0ms in front and 651ms behind.
         *
         * <p>A burst the connection dies in the middle of is never flushed and is lost in silence -
         * the same strict reading {@link SdFileListReader} takes (a partial answer reported as the
         * whole one is worse than no answer, and for {@code M115} specifically a capability missing
         * from a truncated report reads as one the firmware does not have), and what this transport
         * already does to every other exchange in flight on a dead socket.
         *
         * <p><strong>This is the first operator in this pipeline with a prefetch</strong>
         * ({@code XS_BUFFER_SIZE}, 32), so this fold is no longer driven by
         * {@link ReplaySubscriber}'s unbounded demand alone. That mattered while {@link #frame} was
         * a {@code flatMap} over individual characters, which depended on exactly that unbounded
         * demand to avoid interleaving two chunks (issue #149 removed the other half of it, the
         * framing's reliance on being fed by one netty thread, by giving {@link #frame} no inner
         * publisher at all - see its javadoc). It is still one {@code concatMapIterable} stage with
         * the same 32-element buffer, now serving three {@code offer}/{@code flush} pairs instead of
         * two, and the fold is fully synchronous with no inner publisher of its own either.
         * {@code ReplyFluxCapabilitiesTest} and {@code ReplyFluxMultilineTest} each drive well past
         * the prefetch (100 lines, three times 32) through the whole three-reader stage and pin that
         * framing is unchanged.
         */
        private List<GRs<?>> accumulate(GRs<?> reply) {
            if (endstops.offer(reply)) {
                return List.of();
            }
            if (settings.offer(reply)) {
                return List.of();
            }
            if (capabilities.offer(reply)) {
                return List.of();
            }
            var out = new ArrayList<GRs<?>>(4);
            EndstopReport endstopReport = endstops.flush();
            if (endstopReport != null) {
                out.add(endstopReport);
            }
            SettingsReport settingsReport = settings.flush();
            if (settingsReport != null) {
                out.add(settingsReport);
            }
            CapabilitiesReport capabilitiesReport = capabilities.flush();
            if (capabilitiesReport != null) {
                out.add(capabilitiesReport);
            }
            out.add(reply);
            return out;
        }

        /**
         * One received line as the reply it says it is, or nothing when no decoder claims it -
         * {@code handle} rather than {@code map} because {@code MarlinRsDecoder} answers null for a
         * line outside what it reads (boot banner, {@code M503} dump, calibration report), which a
         * {@code Flux} may not carry; it goes to the traffic logger instead.
         *
         * <p>A file listing is assembled rather than forwarded: both markers are swallowed as
         * scaffolding, an entry is swallowed because it was never a reply, and a listing something
         * interrupted produces <strong>nothing</strong> rather than a short list reported as the
         * card's contents - see {@code SdFileListRs}.
         *
         * <p>The line is claimed first and once - the report a console needs annotates the
         * <em>line</em>, not what the line produced, and it has to precede all three returns below,
         * including the one that drops an unclaimed line (10 of the 106 lines censused). So
         * {@code End file list} is reported as {@code SdFileListEnd} even though it is the line that
         * assembles the whole {@link SdFileList}, and a listing entry carries no annotation at all,
         * which is honest: it was never a claimable reply.
         */
        private void decode(String line, SynchronousSink<GRs<?>> sink) {
            GRs<?> reply = claim(line);
            traffic.accept(new TerminalTraffic(RECEIVED, line,
                    reply == null ? null : reply.getClass().getSimpleName(),
                    System.currentTimeMillis()));

            SdFileList files = listing.read(line);
            if (files != null) {
                sink.next(files);
                return;
            }
            if (reply == null) {
                // Not "unread" while a list is open: an entry is a line this host understands
                // perfectly well and has just put into the listing being assembled.
                TRAFFIC_LOGGER.debug(listing.isReading() ? "<- (in a listing) {}" : "<- (unread) {}", line);
                return;
            }
            if (reply instanceof SdFileListBegin || reply instanceof SdFileListEnd) {
                return;
            }
            sink.next(reply);
        }

        /**
         * Whichever decoder claims the line, binary first while the mode is on.
         *
         * <p><strong>The order is forced, not a preference.</strong> {@code rs<n>} is a line of both
         * protocols and only the host knows which one is live - see {@link HostTerminal#binaryMode}
         * for the session a wrong answer destroys. {@code :gcode} keeps the two registries apart so
         * that this decision has to be made here, where the mode is known, rather than guessed
         * inside a registry.
         *
         * <p>The fallback is what keeps a transfer legible: measured, {@code echo:SD card ok},
         * {@code echo:Now fresh file: mbp.gco}, {@code Writing to file: mbp.gco},
         * {@code //action:notification mbp.gco} and {@code echo:SD card released} all arrive
         * mid-transfer, and swallowing them would blind the Storage tab for the length of every
         * upload.
         */
        private GRs<?> claim(String line) {
            if (binaryMode.getAsBoolean()) {
                GRs<?> packetReply = MbpRsDecoder.INSTANCE.decode(line);
                if (packetReply != null) {
                    return packetReply;
                }
            }
            return MarlinRsDecoder.INSTANCE.decode(line);
        }

        @Override
        public void subscribe(CoreSubscriber<? super GRs<?>> actual) {
            flux.subscribe(actual);
        }
    }

    /**
     * The outbound half: pulls G-code text, numbers and checksums it, writes it, and replays it when
     * the printer asks.
     *
     * <p>The drain loop owns the send window. {@link GSendWindow} is portable Kotlin, not
     * thread-safe, and touched from different threads - a command pulled on whichever thread the
     * source produces on, an {@code ok}/{@code Resend:} off the netty inbound loop - so none of them
     * touches it directly: {@link #onNext} parks the payload, {@link #handleBackpressure} and
     * {@link #rewind} park an event, and all three call {@link #processRequest()}, whose {@code wip}
     * guard admits one thread at a time to apply the parked work and call {@code send}/
     * {@code acknowledge}/{@code resendFrom}. That also puts every write in one place, which a replay
     * needs: its blocks must reach the wire in original order and before anything pulled since, which
     * a write from the inbound thread could not promise.
     *
     * <p>What a caller may hand in: one bare command per item - no {@code N}, no {@code *} checksum,
     * no terminator, no open comment - framed verbatim by {@code GEncoder.frame}. The line counter is
     * this layer's to set, not a caller's to send: an {@code M110} sent as text would move the
     * firmware's counter without moving this window's and desynchronise the session. This layer does
     * set it - the first block of every session is {@code M110 N0}, and the same block repairs a
     * sequence that has drifted past the window (see {@link #opening}, {@link #resynchronise}); before
     * that, a host reconnecting to a printer that had not been power-cycled could not send anything.
     */
    public class CommandProcessor implements Processor<String, byte[]>, Subscription {

        /**
         * One thing the printer said about the send window, parked for the drain loop. A record
         * rather than the sentinel integer this replaced ({@code ACK = -1} in a {@code Queue<Integer>}
         * else a resend request), which had nowhere to put the line number an advanced {@code ok}
         * carries without it being read as a retransmit request.
         *
         * @param kind   what the printer said
         * @param number the line it named, or null for a bare {@code ok}, which names none
         */
        private record WindowEvent(Kind kind, Integer number) {

            enum Kind {ACKNOWLEDGED, RESEND}

            static WindowEvent acknowledged(Integer number) {
                return new WindowEvent(Kind.ACKNOWLEDGED, number);
            }

            static WindowEvent resend(int number) {
                return new WindowEvent(Kind.RESEND, number);
            }
        }

        Publisher<String> gcode;
        Subscription subscription;
        Subscriber<? super byte[]> subscriber;
        private final Requested subscriptionRequested = new Requested();
        private final Requested requestedFromGCode = new Requested();
        /**
         * Commands written to the printer that it has not acknowledged yet - a {@link Requested}
         * because it needs the same discipline demand does (non-negative, saturating, incremented and
         * decremented from different threads), not because it is demand. It was a {@code volatile int}
         * before: {@code volatile} publishes a write but does not make a read-modify-write atomic.
         */
        private final Requested sentWithoutAck = new Requested();
        /**
         * The printer's own buffer counts, as the last advanced {@code ok} reported them - kept as
         * the {@link AdvancedOkRs} itself rather than a transport-local copy, after a copy here once
         * read the {@code N} field at a position Marlin does not write it, so the window never
         * widened. Starts at one block, the window a printer reporting no counts gets. {@code volatile}
         * because it is written from the inbound thread and read by {@link #canProcess()} while
         * draining - a reference write is atomic, so a snapshot is all this needs.
         */
        private volatile AdvancedOkRs backpressure = new AdvancedOkRs(1, 1, null);
        /** The drain guard: zero when no thread is in {@link #processRequest()}, otherwise the
         * number of calls still to be accounted for. */
        private final AtomicInteger wip = new AtomicInteger();

        /**
         * Spec 8.5's host side, from {@code :gcode}: numbers, frames and keeps what it framed. Not a
         * second copy of {@link #sentWithoutAck} - the two counts are meant to diverge: this field is
         * how much buffer this host believes it occupies, zeroed by a {@code busy:}; the window's
         * {@code getInFlight()} is what this host must still be able to <em>replay</em>, which a
         * {@code busy:} must not touch, since those are the exact bytes a later {@code Resend:} asks for.
         */
        private final GSendWindow window = new GSendWindow();

        /**
         * Payloads pulled from the source and not framed yet, oldest first. A deque, not a queue, so
         * a payload could go back where it came from if the window ever refused it - unreachable
         * today since {@link #canProcess()} reserves the slot before the pull, but the alternative is
         * dropping a command in silence, which this module exists to prevent.
         */
        private final Deque<String> pending = new ConcurrentLinkedDeque<>();

        /** How many of those there are, so {@link #canProcess()} can hold room for them. */
        private final Requested awaitingFrame = new Requested();

        /** Window events parked in the order the printer stated them - an acknowledgement, or a
         * {@code Resend:}'s line number - one queue because the order is the protocol. */
        private final Queue<WindowEvent> windowEvents = new ConcurrentLinkedQueue<>();

        /** Blocks a {@code Resend:} asked for, framed already, waiting for downstream demand.
         * Touched only from the drain loop, so a plain {@link ArrayDeque} is enough. */
        private final Deque<String> replaying = new ArrayDeque<>();

        /** Binary packets parked by {@link #writeRaw}, waiting for demand. Holds at most one in
         * practice - the Marlin Binary Protocol's window is one packet deep - but a queue rather
         * than a slot so a second can only ever be late, never lost. */
        private final Queue<byte[]> rawPackets = new ConcurrentLinkedQueue<>();

        /**
         * The block that opens the session - {@code M110 N0}, framed - until it has been written.
         * Spec 7.2 names it as how a host starts a session: the window numbers from {@code N1}, and a
         * firmware still powered from a previous session remembers the line it last accepted, so
         * without this every block of a reconnection is rejected with
         * {@code Error:Line Number is not Last Line Number+1} and an unanswerable {@code Resend:} -
         * dead in the outbound direction for good. Measured against the simulator: a plain reconnect
         * reached exactly that state, meaning only the first session after a power-on had ever worked.
         * {@code M110} is exempt from the continuity check for this reason.
         *
         * <p>Also what {@link #resynchronise} parks when a session drifts past repair, so both routes
         * into a fresh sequence are one piece of code. Written through the drain loop like any other
         * block - it costs a unit of demand and a buffer slot until its {@code ok} returns - rather
         * than poked straight at the socket, which would be an unasked {@code onNext} the window never
         * counted.
         */
        private String opening;

        /**
         * Set by a {@code Resend:} and cleared by the {@code ok} that follows it. Marlin answers a
         * rejected block with {@code Error:}, {@code Resend: n}, then {@code ok} - that {@code ok}
         * acknowledges no block, and letting it reach {@link GSendWindow#acknowledge()} would discard
         * the block the rewind just made outstanding again.
         */
        private final AtomicBoolean rewinding = new AtomicBoolean();

        /**
         * Set once the subscriber has been told the stream is over, by either route - needed because
         * the drain loop can be started by a reply, and an {@code ok}/{@code Resend:} arriving after
         * {@link HostTerminal#stop()} would otherwise write what was parked in {@link #pending} behind
         * the terminal signal. Also makes {@link #cancel()} idempotent. A flag, not serialisation: a
         * cancel landing between the check and the write still races, since cancellation bypasses the
         * drain loop - closing that needs the loop itself to own the terminal signal, a shape change
         * rather than a field change.
         */
        private final AtomicBoolean terminated = new AtomicBoolean();

        /**
         * Set when the G-code source is exhausted; the drain loop turns it into a completion.
         * Separate from {@link #terminated} because the source running out is not the end of the
         * stream until what it already handed over has been written - see
         * {@link #completeIfDrained()}.
         */
        private final AtomicBoolean sourceComplete = new AtomicBoolean();

        private final Supplier<String> report = () -> format("(s=%s, r=%s u=%s w=%s n=%d/%d b=%s)",
                subscriptionRequested, requestedFromGCode, awaitingFrame, sentWithoutAck,
                window.getInFlight(), window.getCapacity(), backpressure);

        public CommandProcessor(Publisher<String> gCode) {
            this.gcode = gCode;
            this.opening = window.reset(0);
        }

        /**
         * One {@code ok}: a free slot in the printer's buffer, and usually one block released from
         * the send window - not the {@code ok} closing a resend handshake, which frees nothing this
         * host is still counting, since {@link #rewind} already recorded that the firmware discarded
         * the rejected block and everything after it unacknowledged. Marlin's advanced form,
         * {@code ok N5 P15 B3}, carries its own line number through to the window rather than being
         * counted: counting cannot self-correct - a lost or extra {@code ok} shifts the window's
         * correspondence permanently, and an unknown number is answered with an empty list, not a
         * complaint.
         */
        public void handleBackpressure(OkRs<?> ok) {
            Integer acknowledged = null;
            if (ok instanceof AdvancedOkRs advanced) {
                backpressure = advanced;
                acknowledged = advanced.getLineNumber();
            }
            if (rewinding.compareAndSet(true, false)) {
                log.info("OUT: ok closing a resend - credit only, it acknowledges no block: {} {}",
                        ok.encode(), report.get());
            } else {
                sentWithoutAck.dec();
                windowEvents.add(WindowEvent.acknowledged(acknowledged));
                log.info("OUT: backpressure applied: {} {}", ok.encode(), report.get());
            }
            outSide.processRequest();
        }

        /** Nothing is in flight that the printer will acknowledge, so its buffer is all of it.
         * {@link #window} is deliberately left alone - a stalled machine has not accepted those
         * blocks, and a {@code Resend:} for them is the likeliest thing next. */
        void clearInFlight() {
            sentWithoutAck.reset();
        }

        /**
         * How many more commands may be pulled: the subscriber's demand, bounded by the printer's
         * buffer and the send window, whichever is tightest - both bounds reduced by commands already
         * spoken for ({@code requestedFromGCode}, {@code awaitingFrame}), since each will occupy a
         * slot the moment it arrives. The window term is what makes a refused {@code send} impossible:
         * a block stays in the window until acknowledged, so the host may never hold more outstanding
         * than it can replay. Zero while a replay is waiting for demand (nothing newer may jump ahead
         * of blocks the firmware is sitting on), while the session's {@code M110} is owed (no agreed
         * sequence yet for a block to take a place in - see {@link #opening}), or while a binary
         * packet is parked (the firmware is reading packets, so an ASCII block pulled now would be
         * swallowed as packet bytes - see {@link #writeRaw}).
         */
        long canProcess() {
            if (!replaying.isEmpty() || opening != null || !rawPackets.isEmpty()) {
                return 0;
            }
            long spokenFor = requestedFromGCode.get() + awaitingFrame.get();
            return min(subscriptionRequested.get(),
                    min(backpressure.getBlockQueue() - sentWithoutAck.get() - spokenFor,
                            window.getCapacity() - window.getInFlight() - spokenFor));
        }

        /**
         * Moves as much of the subscriber's demand upstream as the printer currently has room for.
         * The only place the three counters move against each other, so it has to be one step: atomic
         * counters make each one safe alone, but not {@code canProcess() -> inc/dec -> request(n)} as
         * a sequence, and two threads - a {@code request(n)} and an {@code ok} off the netty loop -
         * could each read the same room and claim it. {@code wip} serialises the section instead of a
         * lock: the first caller in runs the loop, a later one leaves a mark and returns, and the
         * thread inside goes round again - which also makes re-entrancy safe, since {@link #onNext}
         * calls this from inside {@code subscription.request}.
         */
        void processRequest() {
            if (wip.getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            for (; ; ) {
                if (!terminated.get()) {
                    applyWindowEvents();
                    write();
                    completeIfDrained();
                    long canProcess = canProcess();
                    if (canProcess > 0 && subscription != null) {
                        requestedFromGCode.inc(canProcess);
                        subscriptionRequested.dec(canProcess);
                        log.info("Process request {} of {} but {}", canProcess, subscriptionRequested, report.get());
                        subscription.request(canProcess);
                    }
                }
                missed = wip.addAndGet(-missed);
                if (missed == 0) {
                    return;
                }
            }
        }

        /**
         * Applies the acknowledgements and resend requests the inbound side parked, in order - drain
         * loop only, and the order is the protocol's own: an {@code ok} ahead of a {@code Resend:}
         * released a block the firmware really took, and applying them reversed would rewind over it.
         *
         * <p>The acknowledgement signal has to be emitted from here: the window is the only thing
         * that knows which block an {@code ok} answered (a bare {@code ok} carries no number, only
         * the window's FIFO order), and the window may only be touched from this loop - which is why
         * {@link HostTerminal#replays} could no longer be an unsafe sink.
         */
        private void applyWindowEvents() {
            for (WindowEvent event; (event = windowEvents.poll()) != null; ) {
                if (event.kind() == WindowEvent.Kind.RESEND) {
                    rewind0(event.number());
                    continue;
                }

                // Spec 8.5's in-order acknowledgement: a named `ok` releases that block and
                // everything still held in front of it (lost acknowledgements); a bare one has only
                // the window's FIFO order to go on.
                int before = window.getInFlight();
                Integer released = event.number() == null
                        ? window.acknowledge()
                        : window.acknowledge(event.number());

                if (released == null) {
                    log.debug("OUT: ok acknowledging no block this window holds {} {}",
                            event.number() == null ? "(unnumbered)" : "N" + event.number(), report.get());
                    continue;
                }
                int releasedCount = before - window.getInFlight();
                if (releasedCount > 1) {
                    log.info("OUT: ok N{} released {} blocks - {} acknowledgement(s) were lost {}",
                            released, releasedCount, releasedCount - 1, report.get());
                }
                emit(new BlockAcknowledged(released, window.getInFlight()));
            }
        }

        /**
         * Spec 8.5 step 5: put the blocks from {@code from} onwards back in hand, byte for byte. The
         * printer's buffer count is rebuilt, not adjusted, since a {@code Resend:} means the firmware
         * discarded the rejected block and everything after it unacknowledged - what it will occupy
         * next is the replay and nothing else. An empty replay means the session has drifted further
         * than a replay can repair (the firmware asked for a block this host no longer holds, which
         * cannot happen in a well-behaved session); see {@link #resynchronise}.
         */
        private void rewind0(int from) {
            List<String> replay = window.resendFrom(from);
            if (replay.isEmpty()) {
                resynchronise(from);
                return;
            }
            sentWithoutAck.reset();
            sentWithoutAck.inc(replay.size());
            log.warn("OUT: Resend: {} - replaying {} block(s) {}", from, replay.size(), report.get());
            replaying.addAll(replay);
        }

        /**
         * Starts the numbering again: reached when the firmware asks for a block the window no
         * longer holds, so there is no sequence left both ends agree on and every later block would
         * be rejected on the same grounds. The abandoned blocks are dropped, loudly - they cannot be
         * replayed under a new number without re-framing, and the window holds the <em>bytes</em> it
         * emitted rather than the commands behind them (spec 8.3 makes the checksum a property of the
         * bytes); re-reading them to renumber would put a second parser back in the transport, and
         * would be unsafe besides, since a relative move replayed under a new number is a move made
         * twice.
         *
         * <p>The layer above is told: these are lines of a job that will never reach the machine, and
         * a print that lost them used to run to end of file and report {@code COMPLETED}. A
         * {@link BlocksAbandoned} goes on the bus instead - {@code JobFeeder} fails the job on it,
         * since it cannot be retried from a checkpoint that records what was sent and not what was
         * accepted. The count is read before the reset and the event emitted after, through
         * {@link HostTerminal#emit} on this drain loop (same reason as {@link BlockAcknowledged}: this
         * is the thread allowed to read the window). Nothing abandoned, nothing announced - a
         * resynchronisation that dropped nothing cost nothing, and without the gate the firmware's
         * pile-up of identical unanswerable {@code Resend:}s would announce one lost print three times.
         */
        private void resynchronise(int from) {
            int abandoned = window.getInFlight();
            opening = window.reset(0);
            sentWithoutAck.reset();
            log.warn("OUT: Resend: {} - not held by the send window, so the session is out of sync;"
                            + " resynchronising with {} and abandoning {} block(s) {}",
                    from, opening, abandoned, report.get());
            if (abandoned > 0) {
                emit(new BlocksAbandoned(from, abandoned));
            }
        }

        /**
         * Writes what is owed: the session's {@code M110} first (nothing may go out ahead of it, or
         * the firmware rejects the gap), then a parked binary packet (the firmware is reading packets
         * and an ASCII block between two of them is bytes inside a packet), then replay (its blocks
         * carry numbers the firmware is waiting for), then what was pulled since. A replayed block or
         * a packet spends a fresh unit of the
         * subscriber's demand - the unit it spent when first written is gone, and no {@code onNext}
         * goes out unasked even for a retransmission - which is why each of the three stages returns
         * rather than falling through once demand runs out. See {@link #opening}.
         */
        private void write() {
            if (opening != null) {
                if (subscriptionRequested.get() <= 0) {
                    return;
                }
                subscriptionRequested.dec();
                sentWithoutAck.inc();
                String reset = opening;
                opening = null;
                write0(reset);
            }
            while (!rawPackets.isEmpty() && subscriptionRequested.get() > 0) {
                subscriptionRequested.dec();
                writeRaw0(rawPackets.poll());
            }
            if (!rawPackets.isEmpty()) {
                return;
            }
            while (!replaying.isEmpty() && subscriptionRequested.get() > 0) {
                subscriptionRequested.dec();
                write0(replaying.pollFirst());
            }
            if (!replaying.isEmpty()) {
                return;
            }
            for (String payload; (payload = pending.pollFirst()) != null; ) {
                String framed = window.send(payload);
                if (framed == null) {
                    pending.addFirst(payload);
                    log.warn("OUT: the send window refused a command already pulled {}", report.get());
                    return;
                }
                awaitingFrame.dec();
                sentWithoutAck.inc();
                write0(framed);
            }
        }

        /** One framed ASCII block, terminated and encoded. {@code US_ASCII} and not the platform
         * default: spec 3 makes a block seven-bit, so a payload that is not is a bug upstream rather
         * than something to re-encode. */
        private void write0(String block) {
            if (terminated.get()) {
                return;
            }
            TRAFFIC_LOGGER.debug("-> {}", block);
            reportTraffic(new TerminalTraffic(SENT, block, null, System.currentTimeMillis()));
            subscriber.onNext((block + "\n").getBytes(US_ASCII));
        }

        /**
         * One binary packet, verbatim - no {@code N}, no checksum, and nothing told to
         * {@link #window}; see {@link #writeRaw} - followed by one {@code \n} that is not part of it.
         *
         * <p>The {@code \n} is for whatever sits between this socket and the firmware's UART, not
         * for the firmware. ESP3D's telnet bridge forwards to the serial port only on a {@code \n}
         * or after 1500ms of silence ({@code TIMEOUT_TELNET_FLUSH} in {@code telnet_server.cpp}),
         * while Marlin abandons a packet whose next byte is 500ms late ({@code packet_max_wait}).
         * Measured 2026-11-17 against ESP3D 3.0.0.8b1 in front of Marlin bugfix-2.1.x: SYNC and
         * QUERY answered only after 1.5s, and FILE OPEN - whose length byte is {@code 0A} - was cut
         * after {@code AD B5 01 11 0A}, answered {@code Datastream timeout} and {@code rs1}, and
         * every resend was cut the same way, so the transfer never got past opening the file.
         *
         * <p>The firmware drops it unread: between packets {@code BinaryStream} is in
         * {@code PACKET_WAIT}, which hunts for {@code AD B5} with a two-byte window and has no
         * timeout, and a {@code \n} that lands after the CLOSE has switched back to ASCII is an
         * empty line, which Marlin ignores.
         */
        private void writeRaw0(byte[] packet) {
            if (terminated.get()) {
                return;
            }
            String shown = hex(packet);
            TRAFFIC_LOGGER.debug("-> {}", shown);
            reportTraffic(new TerminalTraffic(SENT, shown, null, System.currentTimeMillis()));
            var flushed = Arrays.copyOf(packet, packet.length + 1);
            flushed[packet.length] = '\n';
            subscriber.onNext(flushed);
        }

        /**
         * Parks one Marlin Binary Protocol packet for the drain loop and nothing else - no
         * {@code N}, no {@code *}, and not a word to {@link #window} or
         * {@link #sentWithoutAck}, because a packet is not a numbered block and crediting one
         * against the ASCII window would desynchronise the session the moment binary mode ends.
         *
         * <p>It does spend a unit of the subscriber's demand, like any other {@code onNext}: Reactive
         * Streams has no exemption for a packet, and an unasked write is the exact failure the send
         * window exists to prevent.
         *
         * <p>Called from the netty inbound loop (a reply drove the state machine on) or from the
         * retry timer's scheduler thread, neither of which may write - so it parks and leaves, the
         * same shape as {@link #onNext} and {@link #rewind}.
         */
        void writeRaw(byte[] packet) {
            rawPackets.add(packet);
            processRequest();
        }

        /**
         * Parks a {@code Resend: n} for the drain loop, and marks the {@code ok} behind it.
         *
         * <p>Called from the inbound thread, which may not touch the window and may not write.
         */
        void rewind(int lineNumber) {
            rewinding.set(true);
            windowEvents.add(WindowEvent.resend(lineNumber));
            processRequest();
        }

        @Override
        public void subscribe(Subscriber<? super byte[]> s) {
            gcode.subscribe(this);
            subscriber = s;
            subscriber.onSubscribe(this);
        }

        @Override
        public void request(long n) {
            subscriptionRequested.inc(n);
            log.info("Subscription Request {} {}", n, report.get());
            processRequest();
        }

        /**
         * Idempotent, and the stream stays over: a second call and a late reply both do nothing. No
         * longer answers a cancel with {@code onComplete} (rule 2.4 forbids a terminal signal on a
         * cancel path) - that used to re-enter {@code StepVerifier}'s state machine on every
         * expectation mismatch and spin the build at 100% CPU instead of reporting it. Closing the
         * connection is {@code PrinterReactor.disconnect}'s job via {@link HostTerminal#disposeConnection};
         * this class only ever had two lambdas, never the {@code Connection}, so faking a completion
         * was the only way it could end a session before.
         */
        @Override
        public void cancel() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            subscriptionRequested.reset();
            log.info("Subscription Cancel {}", report.get());
            if (subscription != null) {
                subscription.cancel();
            }
        }

        @Override
        public void onSubscribe(Subscription s) {
            log.info("OUT onSubscription {}", s);
            this.subscription = s;
        }

        /**
         * One command off the source: parked, not written. Runs on whatever thread the source
         * produces on ({@link PublisherQueue} pushes from {@code addPublisher} as readily as from
         * {@code request}), so it may neither frame nor write - both go to the drain loop. Counted
         * into {@link #awaitingFrame} before {@link #requestedFromGCode} is decremented, so the room
         * it is about to occupy is never momentarily unaccounted for in {@link #canProcess()}.
         */
        @Override
        public void onNext(String gCode) {
            awaitingFrame.inc();
            requestedFromGCode.dec();
            pending.addLast(gCode);
            log.info("GCode Next {} {}", gCode, report.get());
            processRequest();
        }

        @Override
        public void onError(Throwable t) {
            log.warn("GCode Error {}", report.get(), t);
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            subscriber.onError(t);
        }

        /**
         * The source is exhausted, but not necessarily written out yet - completing directly here
         * would drop commands, since a payload sits in {@link #pending} until there is demand to
         * write it and {@link #write0} refuses once {@code terminated} is set. So this only records
         * that the source is done; the drain loop decides when that becomes a completion, in
         * {@link #completeIfDrained()}.
         */
        @Override
        public void onComplete() {
            log.info("GCode Complete {}", report.get());
            sourceComplete.set(true);
            processRequest();
        }

        /**
         * Completes the subscriber once the source is done <em>and</em> everything owed - the
         * session's {@code M110}, a requested replay, payloads pulled but not yet framed - is on the
         * wire. Drain loop only, so "anything left to write" is asked on the one thread that could be
         * writing. It does not wait for acknowledgements: whether the firmware has taken a block is a
         * different question, answered block by block by {@code BlockAcknowledged}.
         */
        private void completeIfDrained() {
            if (!sourceComplete.get() || terminated.get()) {
                return;
            }
            if (opening != null || !replaying.isEmpty() || !pending.isEmpty() || !rawPackets.isEmpty()) {
                return;
            }
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            log.info("OUT: the source is exhausted and everything it gave us is written {}", report.get());
            subscriber.onComplete();
        }
    }

    public class ReplaySubscriber implements Subscriber<GRs<?>> {

        private Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
            this.subscription = s;
            subscription.request(Long.MAX_VALUE);
        }

        public void handleCancel() {
            if (subscription != null) {
                subscription.cancel();
            }
            subscription = null;
        }

        /**
         * Routes one decoded reply: half into the send window, half onto the bus. Matched on the
         * family interfaces, not concrete classes, so a reply type added to {@code :gcode} under
         * {@link OkRs} (bare, advanced or temperature-carrying - all acknowledgements) or
         * {@link BusyRs} (bare {@code busy:} and Marlin's {@code echo:busy:}) arrives already routed;
         * the arms are a sequence rather than a partition, since {@code OkTemperatureRs} is an
         * {@link OkRs} first.
         *
         * <p>{@code ErrorRs} has no arm, deliberately - it falls to the default and is published as a
         * value, since on a Marlin an error is routine traffic the printer recovers from by itself:
         *
         * <pre>
         * &gt;&gt; N3 M105*1
         * &lt;&lt; Error:checksum mismatch, Last Line: 2
         * &lt;&lt; Resend: 3
         * &lt;&lt; ok
         * </pre>
         *
         * <p>Routing it to {@code tryEmitError} instead used to terminate {@link HostTerminal#messageFlux()}
         * for the rest of the session over one mistyped checksum, while the printer answered normally.
         * A terminal signal there now means only that the inbound side is gone - {@link #onError} and
         * {@link #onComplete} below. Which errors are fatal is not decided here: {@code :gcode} keeps
         * the machine's own words as text, and that judgement belongs to the host.
         */
        @Override
        public void onNext(GRs<?> reply) {
            log.info("IN: onNext({})", reply);
            MbpTransferDriver driver = binaryDriver;
            if (driver != null) {
                // Every reply, not only the MbpRs ones: the signal that the mode really changed is
                // BinaryModeSwitched, an echo line, and only the driver is waiting for it.
                driver.onReply(reply);
            }
            switch (reply) {
                // Consumed by the driver above. Packet-stream traffic the transport owns, like an
                // advanced ok or a Resend:, and an MbpOk must never credit the ASCII window.
                case MbpRs<?> _ -> {
                }
                case OkRs<?> ok -> onOk(ok);
                case BusyRs<?> busy -> onBusy(busy);
                case WaitRs _ -> onWait();
                case ResendRs resend -> onResend(resend.getLineNumber());
                default -> emit(reply);
            }
        }

        @Override
        public void onError(Throwable t) {
            log.error("IN: onError()", t);
            endBus(t);
        }

        @Override
        public void onComplete() {
            log.warn("IN: onComplete()");
            endBus(null);
        }
    }

}
