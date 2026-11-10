package org.qw3rtrun.p3d.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.BlockAcknowledged;
import org.qw3rtrun.p3d.api.msg.BlocksAbandoned;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.SessionStarted;
import org.qw3rtrun.p3d.g.marlin.command.AbsolutePositioning;
import org.qw3rtrun.p3d.g.marlin.command.AutoHome;
import org.qw3rtrun.p3d.g.marlin.command.DeleteSDFile;
import org.qw3rtrun.p3d.g.marlin.command.FactoryReset;
import org.qw3rtrun.p3d.g.marlin.command.FanOff;
import org.qw3rtrun.p3d.g.marlin.command.FullShutdown;
import org.qw3rtrun.p3d.g.marlin.command.GetCurrentPosition;
import org.qw3rtrun.p3d.g.marlin.command.InitSDCard;
import org.qw3rtrun.p3d.g.marlin.command.LinearMoveG1;
import org.qw3rtrun.p3d.g.marlin.command.ListSDCard;
import org.qw3rtrun.p3d.g.marlin.command.PrintTravelMoveLimits;
import org.qw3rtrun.p3d.g.marlin.command.RelativePositioning;
import org.qw3rtrun.p3d.g.marlin.command.ReleaseSDCard;
import org.qw3rtrun.p3d.g.marlin.command.ReportHotendTemperature;
import org.qw3rtrun.p3d.g.marlin.command.ReportSettings;
import org.qw3rtrun.p3d.g.marlin.command.RestoreSettings;
import org.qw3rtrun.p3d.g.marlin.command.SaveSettings;
import org.qw3rtrun.p3d.g.marlin.command.SelectSDFile;
import org.qw3rtrun.p3d.g.marlin.command.SetAdvancedSettings;
import org.qw3rtrun.p3d.g.marlin.command.SetAxisStepsPerUnit;
import org.qw3rtrun.p3d.g.marlin.command.SetBedTemperature;
import org.qw3rtrun.p3d.g.marlin.command.SetFanSpeed;
import org.qw3rtrun.p3d.g.marlin.command.SetGetLineNumber;
import org.qw3rtrun.p3d.g.marlin.command.SetHomeOffsets;
import org.qw3rtrun.p3d.g.marlin.command.SetHotendTemperature;
import org.qw3rtrun.p3d.g.marlin.command.SetMaxFeedrate;
import org.qw3rtrun.p3d.g.marlin.command.SetStartingAcceleration;
import org.qw3rtrun.p3d.g.marlin.command.StartOrResumeSDPrint;
import org.qw3rtrun.p3d.g.marlin.command.StartSDWrite;
import org.qw3rtrun.p3d.g.marlin.command.StopSDWrite;
import org.qw3rtrun.p3d.g.marlin.command.TemperatureAutoReport;
import org.qw3rtrun.p3d.g.marlin.command.ValidateEEPROMContents;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.EepromReport;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.PositionRs;
import org.qw3rtrun.p3d.g.marlin.event.SdCardRs;
import org.qw3rtrun.p3d.g.marlin.event.SettingsRs;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.UnknownCommand;
import org.qw3rtrun.p3d.g.protocol.BusyRs;
import org.qw3rtrun.p3d.g.protocol.ErrorRs;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.terminal.GSender;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The printer aggregate: one machine's connection, firmware/capability profile, declared spec,
 * EEPROM settings, SD card state and last temperature, plus the dispatch that routes every command
 * and reply through it. One instance per printer, holding no scheduler of its own.
 *
 * <h3>Threading: every mutator is this machine's thread, and the exceptions are reads</h3>
 * Every method that <em>changes</em> anything here is reached only from that printer's own
 * {@code p3d-printer-<uuid>} thread, because {@link PrinterReactor} routes all of them through its
 * executor (#80 - before it, {@code handle(ConnectCmd)} ran on an HTTP thread and {@link #declared}
 * on the {@code boundedElastic} worker serving a {@code PUT}, so this paragraph was half true and
 * nothing said which half). So {@link #spec} and {@link #g} are plain fields, and
 * {@code connect()}'s "is an attempt already in flight" is serialised rather than merely visible.
 * Three things are still read off another thread and each is published behind one {@code volatile}
 * reference, never read-modify-written: {@link #blocksInFlight} (a progress poll),
 * {@link ConnectionLifecycle}'s transition fields ({@code PrinterReactor.linked()}), and the one
 * immutable value apiece in the four controls ({@link #snapshot()}, read by a subscriber attaching).
 *
 * <h3>Profile vs spec</h3>
 * {@link #profile} is what the machine <em>reported</em> (seeded from a {@link MachineProfile},
 * replaced wholesale by each firmware report); {@link #spec} is what a person <em>declared</em>
 * ({@link #declared}, the only thing that changes it). The two are never merged - a firmware
 * reconnect must not erase an override - and resolved only where a caller needs one answer
 * ({@link #extruderCount()}).
 *
 * <h3>Dispatch: a switch, not reflection</h3>
 * {@link #onEvent} and {@link #onCommand} replaced a reflective invoker that resolved a handler by
 * walking the message's supertype graph: renaming a handler below silently unregistered it, with no
 * symptom beyond a {@code No invoker for} at WARN - which is how a real regression (a temperature
 * that simply stopped moving) once reached production. Neither switch is exhaustive in the
 * compiler's sense ({@code GRq} has 295 implementations, and a reply may be any {@code :gcode} type
 * or one of the farm's own events) but every arm is still type-checked, so a renamed or removed
 * handler now fails the build instead.
 *
 * <h3>Both directions default to "pass it on", never "drop it"</h3>
 * An unnamed reply is republished ({@link #republish}) and an unnamed command is sent as given
 * ({@link #passThrough}). Both used to default to a silently dropped, WARN-logged no-op, and that
 * shipped three complete features dead: a position report, then both halves of the Storage tab,
 * each tested and returning 200 with nothing reaching the machine. What this trades away: a command
 * that needed a decision and has no handler is now sent raw rather than dropped.
 *
 * <h3>Every reply moves the connection lifecycle, except two that say something more specific</h3>
 * {@link BusyRs} and {@link ErrorRs} drive their own transition; every other reply calls
 * {@link #heard()}, which is what clears a recoverable {@code ERROR} and leaves {@code BUSY}. This
 * used to fire on any traffic at all, and a live run showed why that is wrong: a previous session's
 * {@code M155 S1} auto-reports and a backlog queued while nothing was listening both arrive at a
 * host whose outbound half is dead - so {@code ONLINE -> IDLE} now waits specifically for
 * {@link #on(BlockAcknowledged)}, the one reply that proves this session's own output is received.
 *
 * <h3>A command with no link is refused outright</h3>
 * Used to be a {@code NullPointerException} ({@link GSender} is held only while linked), surfacing
 * as a 500 that pointed at this aggregate rather than at the unreachable machine.
 */
@Slf4j
public class PrinterState implements PrinterAggregate {

    @Getter
    private final UUID id;

    private final ProfileControl profile;

    private final TemperatureControl temperature = new TemperatureControl();

    private final SdCardControl card = new SdCardControl();

    private final EEPROMSettingsControl settings = new EEPROMSettingsControl();

    private final ConnectionLifecycle connection;

    // confined to this machine's aggregate thread: PrinterReactor.declared queues the edit there
    // rather than applying it on the roster's worker (#80). It was volatile for a day, which was
    // a patch over that - the only edge the field had, bought on the way past while investigating
    // #93 and admitted at the time not to be its cause (it moved the measured failure rate not at
    // all, because the write was visible and simply had the wrong value). #93 itself was an
    // announcement undoing an edit 1.5 ms after it and a command refused and never retried, which
    // is MSpecDeclaredEvent's KDoc and is not a threading problem at all.
    @Getter
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
        this.profile = new ProfileControl(seed);
        this.spec = declared == null ? MachineSpec.undeclared() : declared;
        this.connection = connection;
    }

    /** What this machine reported about itself, never null. */
    public MachineProfile getProfile() {
        return profile.get();
    }

    /**
     * A snapshot of the send window: blocks taken and not yet acknowledged - see plan item C4.
     *
     * <p>The one field here deliberately left {@code volatile} rather than confined (#80): it is
     * written only by {@link #on(BlockAcknowledged)} on this machine's thread and read by a
     * progress timer on another, so it is a value the aggregate <em>publishes</em> rather than a
     * question asked of it. Routing the read through the executor would put a progress poll behind
     * every reply queued ahead of it, for a number that is a snapshot either way.
     */
    @Getter
    private volatile int blocksInFlight;

    /** Records an edit - the only way {@link #spec} changes; never touches the profile. */
    public void declared(MachineSpec declared) {
        this.spec = declared == null ? MachineSpec.undeclared() : declared;
    }

    /** How many tools to believe this machine has - see {@link MachineSpec#extruderCountOr}. */
    public int extruderCount() {
        return spec.extruderCountOr(profile.get());
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

    // no-op until setEmitter() replaces it, so an aggregate can be built and seeded before its
    // reactor exists without throwing.
    private Consumer<Object> emitter = event -> {
    };

    // the live link's sender, held only while there is one. Plain, and confined to this machine's
    // aggregate thread: written by onOnline/onOffline/onGaveUp and read by every command arm, all
    // of which PrinterReactor now reaches through its executor - including handle(ConnectCmd),
    // whose M155 S0 used to be read off this field from an HTTP thread (#80).
    private GSender g;

    public void onEvent(Object event) {
        switch (event) {
            case BlockAcknowledged e -> on(e);
            case BlocksAbandoned e -> on(e);
            case BusyRs<?> e -> on(e);
            case ErrorRs e -> on(e);
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
            case PositionRs e -> {
                heard();
                on(e);
            }
            case SdCardRs<?> e -> {
                heard();
                on(e);
            }
            case SettingsRs<?> e -> {
                heard();
                on(e);
            }
            case UnknownCommand e -> {
                heard();
                on(e);
            }
            default -> {
                heard();
                republish(event);
            }
        }
    }

    /** Passes on a reply with no named handler - the inbound counterpart of {@link #passThrough}. */
    private void republish(Object event) {
        log.debug("nothing for the aggregate to decide about {}, passing it on", event);
        emitter.accept(event);
    }

    public void onCommand(GRq<?> command) {
        if (!connection.linked()) {
            log.warn("{} is {}, so {} was not sent", id, connection.state(), command);
            return;
        }
        switch (command) {
            // decided: refused if incomplete, checked against extruderCount(), scaled for the wire.
            case SetHotendTemperature c -> handle(c);
            case SetBedTemperature c -> handle(c);
            case ReportHotendTemperature c -> handle(c);
            case TemperatureAutoReport c -> handle(c);

            // motion, fan, position: no decision yet (see handle(LinearMoveG1) for the bed-volume
            // check that is missing).
            case AutoHome c -> handle(c);
            case LinearMoveG1 c -> handle(c);
            case RelativePositioning c -> handle(c);
            case AbsolutePositioning c -> handle(c);
            case SetFanSpeed c -> handle(c);
            case FanOff c -> handle(c);
            case GetCurrentPosition c -> handle(c);

            // EEPROM (#88): only M503 needs a decision (see handle(ReportSettings)). M301/M200/
            // M145/M149/G21 are deliberately not named here and reach the machine through the
            // default pass-through.
            case SaveSettings c -> handle(c);
            case RestoreSettings c -> handle(c);
            case FactoryReset c -> handle(c);
            case ReportSettings c -> handle(c);
            case ValidateEEPROMContents c -> handle(c);
            case SetAxisStepsPerUnit c -> handle(c);
            case SetMaxFeedrate c -> handle(c);
            case PrintTravelMoveLimits c -> handle(c);
            case SetStartingAcceleration c -> handle(c);
            case SetAdvancedSettings c -> handle(c);
            case SetHomeOffsets c -> handle(c);

            // the card; writing one is a stream (M28..M29) that holds the connection, so it goes
            // through PublisherQueue, not here - see the refusal below.
            case InitSDCard c -> handle(c);
            case ReleaseSDCard c -> handle(c);
            case ListSDCard c -> handle(c);
            case DeleteSDFile c -> handle(c);
            case SelectSDFile c -> handle(c);
            case StartOrResumeSDPrint c -> handle(c);

            // refused: not dangerous, just commands this route cannot carry correctly.
            case StartSDWrite c -> refuse(c, "M28 turns the wire into a file sink until M29, so "
                    + "every command queued behind it would be written into the file instead of "
                    + "run - use PrinterControl.uploadToCard, which holds the connection");
            case StopSDWrite c -> refuse(c, "M29 ends a transfer this route never began");
            case SetGetLineNumber c -> refuse(c, "the line counter belongs to the send window; an "
                    + "M110 sent as a command moves the firmware's and not the host's, and every "
                    + "block after it is rejected for the rest of the session");
            case FullShutdown c -> refuse(c, "an emergency stop queued behind a print is not one; "
                    + "M112 needs Priority.EMERGENCY, and this route is not that");

            default -> passThrough(command);
        }
    }

    /** Declines a command this route cannot carry, loudly and with the reason - distinct from a silently dropped one. */
    private void refuse(GRq<?> command, String why) {
        log.warn("{} was not sent to {}: {}", command, id, why);
    }

    /** The active hotend's last reading, or null when the machine has not reported one. */
    public HeaterReading getTemperature() {
        return temperature.getHotend();
    }

    /**
     * Everything this aggregate holds, as the events that would have announced it - handed to a
     * late subscriber before anything live (see {@code PrinterReactor.updates()}). Facts only, in a
     * fixed order (connection, profile, card, settings, last temperature); nothing that merely
     * <em>happened</em> - a position, a card listing, an acknowledged or abandoned block - is here,
     * or a late subscriber would see it as having just happened.
     *
     * <p><strong>Everything this method carries is invalidated by {@link SessionStarted}</strong>,
     * which {@link #onOnline} states where it forgets - so a control added here is covered without
     * further work, and a subscriber that attached before a reconnect is told rather than left
     * holding the previous session's answers.
     */
    public List<Object> snapshot() {
        var facts = new ArrayList<Object>();
        facts.add(connection.current());
        facts.addAll(profile.snapshot());
        facts.addAll(card.snapshot());
        facts.addAll(settings.snapshot());
        temperature.current().ifPresent(facts::add);
        return facts;
    }

    /**
     * Two decimals, via {@code BigDecimal} rather than {@code String.format}'s locale-sensitive
     * default - which once put {@code M140 S60,00} on the wire under a comma-decimal locale, not a
     * number at all by the spec's sect. 3.1.
     */
    private static BigDecimal wireTemp(BigDecimal celsius) {
        return celsius.setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code M104} - set a hotend's target. Read from {@code T}, not {@code I} (a material-preset index). */
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

    // ------------------------------------------------------------------ motion, fans, settings

    /** {@code G28} - home the axes the command names, or all of them. */
    public void handle(AutoHome home) {
        passThrough(home);
    }

    /** {@code G1}. Not validated against the bed - see the skill's known-debt list. */
    public void handle(LinearMoveG1 move) {
        passThrough(move);
    }

    /** {@code G91} - relative mode, modal; returned together with the move and the {@code G90} back. */
    public void handle(RelativePositioning relative) {
        passThrough(relative);
    }

    /** {@code G90} - absolute mode. The other half of the modal pair above. */
    public void handle(AbsolutePositioning absolute) {
        passThrough(absolute);
    }

    /** {@code M106} - fan speed, on Marlin's 0..255 PWM scale with the fan index in {@code P}. */
    public void handle(SetFanSpeed fan) {
        passThrough(fan);
    }

    /** {@code M107} - fan off. */
    public void handle(FanOff fan) {
        passThrough(fan);
    }

    // ----------------------------------------------------------------------------- the EEPROM

    /** {@code M500} - writes the live settings; does not invalidate the held report, since it now matches. */
    public void handle(SaveSettings save) {
        passThrough(save);
    }

    /**
     * {@code M501} - reload stored settings. Answered by a bare {@code ok} with no announcement, so
     * the held report is silently stale until re-asked - measured against {@code M503} on MarlinSim:
     * <pre>
     * M503 -&gt; M104 S0 B250 F0.00    (stored)
     * M502 -&gt; ok
     * M503 -&gt; M104 S210 B250 F0.10  (defaults, unannounced)
     * M501 -&gt; ok
     * M503 -&gt; M104 S0 B250 F0.00    (back)
     * </pre>
     * So this handler forgets the held report and re-asks - see {@link #reread()} - rather than
     * inventing a "stale" event.
     */
    public void handle(RestoreSettings restore) {
        passThrough(restore);
        reread();
    }

    /**
     * {@code M502} - load the firmware's compiled-in defaults into live memory; not the same
     * operation as {@code M501} - on MarlinSim the stored settings and the defaults genuinely
     * differ (see {@link #handle(RestoreSettings)}). Neither writes the EEPROM, which is why
     * {@code M501} can undo this and why {@link EEPROMSettingsControl} keeps {@code M504}'s verdict
     * across an invalidation.
     */
    public void handle(FactoryReset reset) {
        passThrough(reset);
        reread();
    }

    /** Forgets the held report and re-asks, in that order; skipped if this firmware has no {@code M503}. */
    private void reread() {
        settings.invalidated();
        if (settings.reportSupported()) {
            passThrough(new ReportSettings());
        }
    }

    /**
     * {@code M503} - what are your settings. Refused once this firmware has answered
     * {@code Unknown command} to it: a {@code DISABLE_M503} build stores settings but does not
     * report them, and {@code MachineProfile.hasEeprom()} cannot tell the difference - only the
     * refusal, reaching {@link EEPROMSettingsControl#on(UnknownCommand)}, can. Sent bare:
     * {@code M503}, {@code M503 S1}, {@code M503 S} and {@code M503 C} were measured
     * byte-identical, and {@code M503 S0} drops the section headings a tab groups by.
     */
    public void handle(ReportSettings report) {
        if (!settings.reportSupported()) {
            refuse(report, "this firmware answered Unknown command to an M503, which is what a "
                    + "build with DISABLE_M503 does - it stores settings and cannot report them, so "
                    + "there is nothing to ask for until the next session");
            return;
        }
        passThrough(report);
    }

    /** {@code M504} - is the EEPROM intact. The failure text is unmeasured; the simulator cannot be asked for a corrupt one. */
    public void handle(ValidateEEPROMContents validate) {
        passThrough(validate);
    }

    // ------------------------------------------------ the six settings this feature may change

    /** {@code M92} - steps per unit. Its {@code steps} field writes {@code X}. */
    public void handle(SetAxisStepsPerUnit steps) {
        passThrough(steps);
    }

    /** {@code M203} - maximum feedrate per axis. */
    public void handle(SetMaxFeedrate feedrate) {
        passThrough(feedrate);
    }

    /** {@code M201} - maximum acceleration per axis. Its {@code accel} field writes {@code X}. */
    public void handle(PrintTravelMoveLimits limits) {
        passThrough(limits);
    }

    /** {@code M204} - print, retract and travel acceleration. Its {@code accel} writes {@code P}. */
    public void handle(SetStartingAcceleration acceleration) {
        passThrough(acceleration);
    }

    /**
     * {@code M205} - jerk and junction deviation. {@code jerk} writes {@code X} and
     * {@code deviation} writes {@code J}.
     */
    public void handle(SetAdvancedSettings advanced) {
        passThrough(advanced);
    }

    /** {@code M206} - home offsets. Its {@code offset} field writes {@code P}. */
    public void handle(SetHomeOffsets offsets) {
        passThrough(offsets);
    }

    /** {@code M114} - where is the toolhead. Answered by a {@code PositionRs} into {@link #on}. */
    public void handle(GetCurrentPosition position) {
        passThrough(position);
    }

    // ------------------------------------------------------------------------------- the card

    /** {@code M21} - mount the card. Answered by {@code echo:SD card ok}, or silence if there is none - see {@link #on(SdCardRs)}. */
    public void handle(InitSDCard mount) {
        passThrough(mount);
    }

    /**
     * {@code M22} - release the card. Should refuse while a print is running from it and does not;
     * {@link SdCardControl} now holds the state ({@code SdPrinting}/{@code SdNotPrinting}) that rule
     * would need, left for its own issue. Forgets the card as a command, not a reply - see
     * {@link SdCardControl#released()}.
     */
    public void handle(ReleaseSDCard release) {
        passThrough(release);
        card.released();
    }

    /** {@code M20} - list the card, sent bare; the long form changes what {@code SdFileListReader} expects. */
    public void handle(ListSDCard list) {
        passThrough(list);
    }

    /** {@code M30 <name>} - delete a file from the card. */
    public void handle(DeleteSDFile delete) {
        passThrough(delete);
    }

    /**
     * {@code M23 <name>} - select a file, half of the pair with {@code M24} that a print is, and
     * the half that carries the name. That is why the pair stands (#109): {@code M23} takes the
     * rest of its line verbatim, where {@code M32} parses its own for parameters, so a {@code p}
     * anywhere in a filename reaches it as {@code P} and turns the print into a subroutine call
     * that runs a second file afterwards.
     */
    public void handle(SelectSDFile select) {
        passThrough(select);
    }

    /**
     * {@code M24} - start or resume printing the selected file. Measured on a file that is open but
     * not started, it is a <em>resume</em> - {@code //action:resume}, and a
     * {@code //action:prompt_begin Resuming SD} prompt sequence - which is cosmetically wrong and
     * is the price of the pair (#109).
     */
    public void handle(StartOrResumeSDPrint start) {
        passThrough(start);
    }

    /** Sends a command as given - the default arm; the inbound counterpart is {@link #republish}. */
    private void passThrough(GRq<?> command) {
        log.info("{}", command);
        g.send(command);
    }

    /**
     * The socket is up: take the sender, forget what the last session taught, say so, and ask what
     * the machine is.
     *
     * <p>The clear and its {@link SessionStarted} are one action, before the early return rather
     * than after it - that return is for a second {@code onOnline} on a machine already
     * {@code ONLINE}, and that path used to clear both controls and announce nothing at all (#94).
     * The boundary leads the {@code ONLINE} transition for the same reason: a page applying
     * messages in order has to forget the old session before it is told there is a new link.
     */
    public void onOnline(GSender g) {
        this.g = g;
        card.sessionStarted();
        settings.sessionStarted();
        emitter.accept(new SessionStarted());
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
     * Connect/disconnect intent - the socket work is {@link PrinterReactor}'s. Turns
     * auto-reporting off before releasing, so a reconnect does not find a backlog instead of a
     * conversation.
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

    /** The link went away; retried or not depending on whether a connection is still wanted. */
    public void onOffline(String why) {
        this.g = null;
        transition(connection.linkFailed(why));
    }

    /** The attempts ran out. Nothing retries after this until someone asks again. */
    public void onGaveUp(String why) {
        this.g = null;
        transition(connection.gaveUp(why));
    }

    /** A block this session put on the wire came back - the one proof the outbound half is alive. */
    public void on(BlockAcknowledged ack) {
        blocksInFlight = ack.inFlight();
        transition(connection.blockAcknowledged());
    }

    /**
     * The send window renumbered and the blocks it held went with it - republished, nothing else.
     * Deliberately moves no connection state either way: the {@code Error:}/{@code Resend:} that
     * always precedes this has already reached {@link #on(ErrorRs)} and faulted the connection, so
     * faulting again would put the same fault on the stream twice; and this is not a reply, so it
     * must not call {@link #heard()} either - that used to read "lost some blocks" as "all clear"
     * and jump straight to {@code IDLE} ({@code PrinterStateDispatchTest} pins the fix). See
     * {@link BlocksAbandoned} for why the job built on this cannot resume from its checkpoint.
     */
    public void on(BlocksAbandoned abandoned) {
        log.warn("{}: {} block(s) this host had framed were abandoned when the session was"
                        + " renumbered after a Resend: for line {} it could not answer",
                id, abandoned.blocks(), abandoned.lineNumber());
        emitter.accept(abandoned);
    }

    /** {@code busy:} - the machine is working and will not answer for a while. */
    public void on(BusyRs<?> busy) {
        transition(connection.busyReported(busy.getReason()));
    }

    /** {@code Error:} - routine Marlin traffic, a state rather than a lost connection; not republished, since the transition is the announcement. */
    public void on(ErrorRs error) {
        transition(connection.faulted(error.getMessage()));
    }

    public void on(TemperatureRs<?> tempEvent) {
        if (temperature.on(tempEvent)) {
            emitter.accept(tempEvent);
        }
    }

    /** Where the toolhead is - republished and not kept; nothing here reads a position. */
    public void on(PositionRs position) {
        emitter.accept(position);
    }

    /**
     * Anything the card said - one arm for the whole {@link SdCardRs} family, so a reply added to
     * it in {@code :gcode} arrives already routed. Kept in {@link SdCardControl} for
     * {@link #snapshot()} and always republished, unlike a temperature: these answer a question
     * someone just asked.
     */
    public void on(SdCardRs<?> reply) {
        card.on(reply);
        emitter.accept(reply);
    }

    /**
     * One settings line or an {@code M504} verdict - one arm for the whole {@link SettingsRs}
     * family, kept in {@link EEPROMSettingsControl} and always republished: an unmoved value is
     * still news to whoever just asked for it.
     */
    public void on(SettingsRs<?> reply) {
        settings.on(reply);
        emitter.accept(reply);
    }

    /**
     * {@code echo:Unknown command: "…"} - always republished; a named arm that swallowed it would
     * mean a page loses the refusal of every command it sends. Only an {@code M503} refusal means
     * anything to {@link EEPROMSettingsControl} - see {@link #handle(ReportSettings)}.
     */
    public void on(UnknownCommand refusal) {
        settings.on(refusal);
        emitter.accept(refusal);
    }

    public void on(FirmwareInfoRs report) {
        if (profile.on(report)) {
            emitter.accept(report);
        }
    }

    public void on(CapabilityRs report) {
        if (profile.on(report)) {
            emitter.accept(report);
        }
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
