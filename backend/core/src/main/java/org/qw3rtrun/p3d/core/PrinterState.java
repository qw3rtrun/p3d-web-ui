package org.qw3rtrun.p3d.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.*;
import org.qw3rtrun.p3d.g.marlin.command.ReportHotendTemperature;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.g.marlin.command.SetBedTemperature;
import org.qw3rtrun.p3d.g.marlin.command.SetHotendTemperature;
import org.qw3rtrun.p3d.g.marlin.command.TemperatureAutoReport;
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

    public PrinterState(UUID id) {
        this(id, MachineProfile.unknown());
    }

    public PrinterState(UUID id, MachineProfile seed) {
        this(id, seed, new ConnectionLifecycle());
    }

    public PrinterState(UUID id, MachineProfile seed, ConnectionLifecycle connection) {
        this.id = id;
        this.profile = seed == null ? MachineProfile.unknown() : seed;
        this.connection = connection;
    }

    /** What this machine reported about itself, never null. */
    public MachineProfile getProfile() {
        return profile;
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
    private Consumer<GEvent> emitter = event -> {
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
     * A reply - any reply - is the only proof that there is a firmware on the other end of the
     * socket rather than just an open port, so it is what takes a machine from {@code ONLINE} to
     * {@code IDLE} and what clears a recoverable {@code ERROR}. Two kinds say something more
     * specific and are not that proof: a busy notice and an error report, which have arms of their
     * own. Before B3 both of those fell through to the debug line.
     *
     * <p>Not exhaustive in the compiler's sense: {@code GEvent} is not sealed, and sealing it would
     * close the application's event vocabulary to the modules that are going to add to it. What the
     * switch does buy is that every arm is type-checked and a handler that is renamed or removed
     * breaks the build instead of going quiet.
     */
    public void onEvent(GEvent event) {
        switch (event) {
            case MachineBusyEvent e -> transition(connection.busyReported(e.reason()));
            case ErrorReportEvent e -> transition(connection.faulted(e.message()));
            case TemperatureReportedEvent e -> {
                heard();
                on(e);
            }
            case FirmwareInfoReportEvent e -> {
                heard();
                on(e);
            }
            case CapabilityReportEvent e -> {
                heard();
                on(e);
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
     * user asked for, and silently doing nothing about it is the failure. The REST surface accepts
     * any Marlin command class, and this is the short list the aggregate actually acts on - the
     * rest are a gap in this layer rather than noise from the machine.
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
            default -> log.warn("no handler for {}, so nothing was sent", command);
        }
    }

    public TemperatureReport getTemperature() {
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
        if (index < profile.extruderCount()) {
            g.m104(index, wireTemp(temperature.getTemp()));
        } else {
            log.warn("According to its profile this machine has only {} extruders, but the command's"
                    + " hotend index is {}", profile.extruderCount(), index);
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

    public void on(TemperatureReportedEvent tempEvent) {
        if (temperatureControl.on(tempEvent)) {
            emitter.accept(tempEvent);
        }
    }

    public void on(FirmwareInfoReportEvent report) {
        var updated = new MachineProfile(report.keyValues(), profile.capabilities(), LocalDateTime.now());
        if (updated.firmware().equals(profile.firmware())) {
            return;
        }
        profile = updated;
        emitter.accept(report);
    }

    public void on(CapabilityReportEvent report) {
        var previous = profile.capabilities().get(report.capability());
        if (previous != null && previous == report.enabled()) {
            return;
        }
        var merged = new HashMap<>(profile.capabilities());
        merged.put(report.capability(), report.enabled());
        profile = new MachineProfile(profile.firmware(), Map.copyOf(merged), LocalDateTime.now());
        emitter.accept(report);
    }

    protected void setEmitter(Consumer<GEvent> emitter) {
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
