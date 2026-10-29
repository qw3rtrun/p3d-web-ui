package org.qw3rtrun.p3d.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.BlockAcknowledged;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.g.marlin.command.ReportHotendTemperature;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.g.marlin.command.SetFanSpeed;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.PositionRs;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
import org.qw3rtrun.p3d.g.protocol.BusyRs;
import org.qw3rtrun.p3d.g.protocol.ErrorRs;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.g.marlin.command.SetBedTemperature;
import org.qw3rtrun.p3d.g.marlin.command.SetHotendTemperature;
import org.qw3rtrun.p3d.g.marlin.command.TemperatureAutoReport;
import org.qw3rtrun.p3d.g.marlin.command.AbsolutePositioning;
import org.qw3rtrun.p3d.g.marlin.command.AutoHome;
import org.qw3rtrun.p3d.g.marlin.command.FanOff;
import org.qw3rtrun.p3d.g.marlin.command.GetCurrentPosition;
import org.qw3rtrun.p3d.g.marlin.command.LinearMoveG1;
import org.qw3rtrun.p3d.g.marlin.command.RelativePositioning;
import org.qw3rtrun.p3d.g.marlin.command.SaveSettings;
import org.qw3rtrun.p3d.terminal.GSender;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
public class PrinterState implements PrinterAggregate {

    @Getter
    private final UUID id;

    /**
     * What this machine is, as far as anyone knows.
     *
     * <p><strong>Seeded from the roster, not discovered from nothing.</strong> It used to be two
     * objects - a {@code FirmwareInfo} wrapping a nullable report and a {@code CapabilitiesInfo}
     * wrapping a map - both starting empty every time the process did. So the extruder-count check
     * below had nothing to read until a machine had been dialled, and a command sent to one that
     * had not blew up on a null rather than being refused.
     *
     * <p>The profile is now a single value that {@code printer-management} persists and announces,
     * so a restart knows what each machine is before anything connects to it. It is replaced
     * wholesale on each change, which is what makes it safe to hand out.
     */
    private MachineProfile profile;

    /**
     * Where this machine is in its connection lifecycle - one enum, and the only answer.
     *
     * <p><strong>It replaced two booleans on this class and one column on the entity.</strong>
     * {@code connected} was set by the connect command and {@code online} by the socket coming up,
     * and the two could say different things: a machine whose link had dropped while nobody asked
     * it to was {@code connected && !online}, which no reader of this class could have interpreted
     * and no event announced. See {@link ConnectionLifecycle} for the transitions and
     * {@code MachineConnectionState} for what each one means.
     */
    private final ConnectionLifecycle connection;

    /**
     * What a human declared about this machine, which the roster keeps and announces.
     *
     * <p><strong>Held beside the profile rather than merged into it</strong>, because a firmware
     * report replaces the profile wholesale and would otherwise erase an override every time a
     * machine reconnected. Plan item B4 draws that line in the schema and it has to hold here too:
     * nothing in this class writes the spec, and nothing a machine says can.
     *
     * <p>It is here for one live reason - the tool-index check below honours a declared extruder
     * count - and one that is coming, which is plan item D1 matching a job against it.
     */
    private MachineSpec spec;

    public PrinterState(UUID id) {
        this(id, MachineProfile.unknown(), MachineSpec.undeclared());
    }

    public PrinterState(UUID id, MachineProfile seed) {
        this(id, seed, MachineSpec.undeclared());
    }

    public PrinterState(UUID id, MachineProfile seed, MachineSpec declared) {
        this(id, seed, declared, new ConnectionLifecycle());
    }

    public PrinterState(UUID id, MachineProfile seed, MachineSpec declared, ConnectionLifecycle connection) {
        this.id = id;
        this.profile = seed == null ? MachineProfile.unknown() : seed;
        this.spec = declared == null ? MachineSpec.undeclared() : declared;
        this.connection = connection;
    }

    /** What this machine reported about itself, never null. */
    public MachineProfile getProfile() {
        return profile;
    }

    /**
     * How many blocks the machine has taken and not acknowledged, as of the last one it did.
     *
     * <p>Kept here because this is where every reply lands, and read by plan item C4 so a job can
     * report what is still in flight rather than claiming that everything sent has been printed.
     * It is a snapshot of the transport's send window and nothing derives from it - the aggregate
     * makes no decision on this number.
     */
    private volatile int blocksInFlight;

    /** See {@link #blocksInFlight}. */
    public int getBlocksInFlight() {
        return blocksInFlight;
    }

    /** What a human declared about this machine, never null. */
    public MachineSpec getSpec() {
        return spec;
    }

    /**
     * Records an edit, which is the one thing that may change the spec.
     *
     * <p>Reached from {@code PrinterReactorManager} when the roster re-announces a machine, so an
     * edit takes effect on a running printer rather than waiting for a restart. It deliberately
     * does not touch the profile: an edit is not something the machine said.
     */
    public void declared(MachineSpec declared) {
        this.spec = declared == null ? MachineSpec.undeclared() : declared;
    }

    /** How many tools to believe this machine has - see {@link MachineSpec#extruderCountOr}. */
    public int extruderCount() {
        return spec.extruderCountOr(profile);
    }

    /** Where the machine's connection is, as a value a caller can be handed. */
    public ConnectionStatus getConnection() {
        return connection.status();
    }

    /** The current connection state as the event that would have announced it. */
    public ConnectionStateChanged connectionAsEvent() {
        return connection.current();
    }

    /** Whether a connection is still wanted - what makes a dropped link worth retrying. */
    public boolean wantsToBeConnected() {
        return connection.wanted();
    }

    private TemperatureControl temperatureControl = new TemperatureControl();

    /**
     * Where state changes go, once anything is listening.
     *
     * <p>A no-op until {@link #setEmitter} replaces it, because an aggregate can now be built and
     * seeded before its reactor exists - and one that cannot announce itself yet should not throw.
     */
    private Consumer<Object> emitter = event -> {
    };
    private GSender g;

    /**
     * One decoded reply from the machine, routed to whatever this aggregate does about it.
     *
     * <p><strong>A switch, and not reflection.</strong> {@code PrinterReactor} used to do this by
     * reflecting over this class's declared methods at construction, keying them by parameter type
     * and resolving a message with a breadth-first walk of its supertype graph. Nothing about that
     * was checked: renaming a handler below unregistered it, and a reply type that gained an
     * interface changed which handler won. The only symptom was a {@code No invoker for} at WARN -
     * nothing threw, the temperature simply stopped moving, which is how a real regression reached
     * production once already.
     *
     * <p>It also put the decision in the wrong class. What this aggregate reacts to is this
     * aggregate's business; the reactor's job is to carry messages, not to work out who wants them.
     *
     * <p><strong>Every reply moves the connection lifecycle, including the ones no arm claims.</strong>
     * A reply after a fault is what clears a recoverable {@code ERROR}, and a reply after the busy
     * notices have stopped is what leaves {@code BUSY}. Two kinds say something more specific and
     * have arms of their own: a busy notice and an error report. Before B3 both of those fell
     * through to the debug line.
     *
     * <p><strong>What a reply does not do is prove the machine is there.</strong> B3 put
     * {@code ONLINE} to {@code IDLE} on "any reply" and a live run showed what that was worth: a
     * previous session's {@code M155 S1} auto-reports, and a backlog queued while nothing was
     * listening, both arrive at a host whose outbound half is dead. That arrow belongs to
     * {@link BlockAcknowledged}, which names a block this session put on the wire and got back.
     *
     * <p><strong>Matched on {@code :gcode}'s own reply types.</strong> A reply arrives here as the
     * {@code GRs} the protocol decoded - there is no longer an {@code api.msg} record standing in
     * for each one - and the family interfaces are what the arms name: {@link TemperatureRs} covers
     * the unsolicited auto-report and the one carried on an {@code ok}, {@link BusyRs} covers the
     * bare {@code busy:} and Marlin's {@code echo:busy:}.
     *
     * <p>Not exhaustive in the compiler's sense: a reply may be anything {@code :gcode} can decode,
     * and the farm's own events are not {@code GRs} at all. What the switch does buy is that every
     * arm is type-checked and a handler that is renamed or removed breaks the build instead of
     * going quiet.
     */
    public void onEvent(Object event) {
        switch (event) {
            case BlockAcknowledged ack -> {
                blocksInFlight = ack.inFlight();
                transition(connection.blockAcknowledged());
            }
            case BusyRs<?> e -> transition(connection.busyReported(e.getReason()));
            case ErrorRs e -> transition(connection.faulted(e.getMessage()));
            case TemperatureRs<?> e -> {
                heard();
                on(e);
            }
            case FirmwareInfoRs e -> {
                heard();
                on(e);
            }
            case CapabilityRs e -> {
                heard();
                on(e);
            }
            /*
             * Republished, not kept.
             *
             * The aggregate has nothing to decide about a position: there is no rule it enforces
             * and no other handler that reads one. Storing it anyway would be the write-only field
             * this module already removed once - `PrinterEntity.connected`, written by nothing and
             * read by nothing - so it goes to whoever is watching and no further.
             *
             * It still needs an arm. An event with no arm reaches `default` and is *dropped*, so a
             * reply that is mapped all the way through the transport can still never appear in a
             * browser - which is exactly how this one behaved until this case existed.
             */
            case PositionRs e -> {
                heard();
                emitter.accept(e);
            }
            default -> {
                heard();
                log.debug("nothing for the aggregate to do about {}", event);
            }
        }
    }

    /**
     * One command from a caller, routed to the handler that knows how to send it.
     *
     * <p><strong>Refused outright when there is no connection.</strong> This used to be a 500 and a
     * {@code NullPointerException}: the {@code GSender} below is held only while a link is up, and
     * every arm dereferences it. The request had bound and routed correctly, so the stack trace
     * pointed at the aggregate rather than at the machine being unreachable, which is the least
     * useful place it could have pointed.
     *
     * <p>Unhandled is a <em>warning</em> here, unlike {@link #onEvent}: a command is something a
     * user asked for, and silently doing nothing about it is the failure. {@code PrinterControl}
     * takes any {@link GRq}, and this is the short list the aggregate actually sends - the rest are
     * a gap in this layer rather than noise from the machine.
     */
    public void onCommand(GRq<?> command) {
        if (!connection.linked()) {
            log.warn("{} is {}, so {} was not sent", id, connection.state(), command);
            return;
        }
        switch (command) {
            case SetHotendTemperature c -> handle(c);
            case SetBedTemperature c -> handle(c);
            case ReportHotendTemperature c -> handle(c);
            case TemperatureAutoReport c -> handle(c);

            // Motion, fans and settings carry no decision this layer gets to make: the application
            // command was already translated into exactly the Marlin command the machine should
            // receive, so these go through as they are. They are still listed one by one rather
            // than caught by a wildcard, because the point of this switch is that adding a command
            // and forgetting to send it is visible here.
            case AutoHome c -> passThrough(c);
            case LinearMoveG1 c -> passThrough(c);
            case RelativePositioning c -> passThrough(c);
            case AbsolutePositioning c -> passThrough(c);
            case SetFanSpeed c -> passThrough(c);
            case FanOff c -> passThrough(c);
            case SaveSettings c -> passThrough(c);
            case GetCurrentPosition c -> passThrough(c);

            default -> log.warn("no handler for {}, so nothing was sent", command);
        }
    }

    public HeaterReading getTemperature() {
        return temperatureControl.getHotend();
    }

/**
     * Two decimals.
     *
     * <p>The {@code String.format("%.2f", …)} in the encoder this replaces used the default locale,
     * so under a comma-decimal locale it put {@code M140 S60,00} on the wire — not a number at all
     * by the spec's §3.1, and a command the firmware would reject. The value now arrives as a
     * {@code BigDecimal} and never passes through a locale, so only the scale is left to set; it is
     * set here rather than in the DSL because the wire format of a temperature is this layer's
     * business.
     */
    private static BigDecimal wireTemp(BigDecimal celsius) {
        return celsius.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * {@code M104} — set a hotend's target.
     *
     * <p>The tool is read from {@code T} and not from {@code I}: Marlin's M104 documents both, with
     * {@code I} a material-preset index, and {@code T} is the one {@code GSender.m104} sends. An
     * absent tool means the active one, which is tool 0 here as it was before.
     */
    public void handle(SetHotendTemperature temperature) {
        log.info("{}", temperature);
        if (temperature.getTemp() == null) {
            log.warn("M104 with no S parameter carries no target temperature: {}", temperature);
            return;
        }
        int index = temperature.getT() == null ? 0 : temperature.getT();
        if (index < extruderCount()) {
            g.m104(index, wireTemp(temperature.getTemp()));
        } else {
            log.warn("This machine has only {} extruders, but the command's hotend index is {}",
                    extruderCount(), index);
        }
    }

    public void handle(SetBedTemperature temperature) {
        log.info("{}", temperature);
        if (temperature.getTemp() == null) {
            log.warn("M140 with no S parameter carries no target temperature: {}", temperature);
            return;
        }
        g.m140(wireTemp(temperature.getTemp()));
    }

    public void handle(ReportHotendTemperature report) {
        log.info("{}", report);
        g.m105(report.getIndex());
    }

    public void handle(TemperatureAutoReport report) {
        log.info("{}", report);
        g.m155(report.getSeconds());
    }

    /**
     * Sends a command the aggregate has nothing to add to.
     *
     * <p>The temperature handlers above exist because they <em>decide</em> something - a missing
     * target is refused, a hotend index is checked against the machine's extruder count, a scale is
     * imposed on the wire. Homing, moving, the fan and {@code M500} have no such decision at this
     * layer, so a handler each would be eight copies of one line.
     *
     * <p><strong>What is deliberately not checked here is the one thing worth checking.</strong> A
     * move is not validated against the bed volume, because a relative move cannot be without
     * knowing the position and the declared volume is often absent. That is recorded as debt
     * rather than half-done.
     */
    private void passThrough(GRq<?> command) {
        log.info("{}", command);
        g.send(command);
    }

    /**
     * The socket is open: take the sender, and ask the machine what it is.
     *
     * <p>The handshake runs only on the transition into {@code ONLINE}, which is what keeps a
     * reconnect from asking twice. It is also why {@link ConnectionLifecycle#linkEstablished()}
     * returns null when nothing moved rather than a no-change event.
     */
    public void onOnline(GSender g) {
        this.g = g;
        var change = connection.linkEstablished();
        if (change == null) {
            return;
        }
        log.info("{} is online", id);
        g.firmwareInfo(); //M115
        g.autoReportTemp(1); //M105
        emitter.accept(change);
    }

    /**
     * Connect or disconnect, as the user asked.
     *
     * <p>Only the intent and the announcement live here. Whether that intent means opening a socket,
     * scheduling a retry or cancelling one in flight is {@link PrinterReactor}'s - this is the
     * aggregate, and it holds no scheduler.
     *
     * <p>Turning auto-reporting off before letting go is deliberate: a printer left auto-reporting
     * at one-second intervals goes on writing temperatures into a socket nobody is reading, and a
     * reconnect would then find a backlog rather than a conversation.
     */
    public void handle(ConnectCmd cmd) {
        if (cmd.connect()) {
            transition(connection.connectRequested());
            return;
        }
        if (connection.linked() && g != null) {
            g.m155(0);
        }
        transition(connection.disconnectRequested());
    }

    /**
     * The link went away, for whatever reason.
     *
     * <p>Where it goes next depends on whether a connection is still wanted: a drop nobody asked
     * for means {@code CONNECTING} and another attempt, and a drop right after Disconnect means
     * {@code OFFLINE}. That is the one distinction the pair of booleans this replaced could not
     * express.
     */
    public void onOffline(String why) {
        this.g = null;
        transition(connection.linkFailed(why));
    }

    /** The attempts ran out. Nothing retries after this until someone asks again. */
    public void onGaveUp(String why) {
        this.g = null;
        transition(connection.gaveUp(why));
    }

    public void on(TemperatureRs<?> tempEvent) {
        if (temperatureControl.on(tempEvent)) {
            emitter.accept(tempEvent);
        }
    }

    public void on(FirmwareInfoRs report) {
        var updated = new MachineProfile(report.getKeyValues(), profile.capabilities(), LocalDateTime.now());
        if (updated.firmware().equals(profile.firmware())) {
            return;
        }
        profile = updated;
        emitter.accept(report);
    }

    public void on(CapabilityRs report) {
        var previous = profile.capabilities().get(report.getCapability());
        if (previous != null && previous == report.getEnabled()) {
            return;
        }
        var merged = new HashMap<>(profile.capabilities());
        merged.put(report.getCapability(), report.getEnabled());
        profile = new MachineProfile(profile.firmware(), Map.copyOf(merged), LocalDateTime.now());
        emitter.accept(report);
    }

    protected void setEmitter(Consumer<Object> emitter) {
        this.emitter = emitter;
    }

    /** The machine said something, whatever it was. See {@link #onEvent}. */
    private void heard() {
        transition(connection.replyReceived());
    }

    /** Announces a transition, if there was one. A null is "nothing moved" and not a failure. */
    private void transition(ConnectionStateChanged change) {
        if (change == null) {
            return;
        }
        log.info("{}: {} -> {}{}", id, change.from(), change.to(),
                change.reason() == null ? "" : " (" + change.reason() + ")");
        emitter.accept(change);
    }
}
