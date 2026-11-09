package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.g.code.core.token.GIdentifier;
import org.qw3rtrun.p3d.g.code.core.token.GNumber;
import org.qw3rtrun.p3d.g.code.core.token.GTokenizer;
import org.qw3rtrun.p3d.g.marlin.event.EepromReport;
import org.qw3rtrun.p3d.g.marlin.event.SettingsRs;
import org.qw3rtrun.p3d.g.marlin.event.SettingsSection;
import org.qw3rtrun.p3d.g.marlin.event.SettingsValue;
import org.qw3rtrun.p3d.g.marlin.event.UnknownCommand;

import java.util.ArrayList;
import java.util.List;

/**
 * What this machine says its own configuration is: {@code M503}'s report, {@code M504}'s verdict,
 * and whether {@code M503} exists on this firmware at all. Same shape as {@link SdCardControl},
 * {@link TemperatureControl} and {@link ProfileControl}: {@code on} folds a reply in,
 * {@link #snapshot()} hands it back as the replies that would have announced it.
 *
 * <p>The report is one ordered list, not three maps - the firmware interleaves section headings and
 * values with no other link between them, so a value's place in the sequence is which heading it is
 * under. A value merges in place on a second read of the same key rather than accumulating, which is
 * what makes a single-setting read-back ({@code >> M92}) update the held report without a second
 * 25-line dump. Nothing is parsed: {@code :gcode}'s {@link SettingsValue} keeps the line verbatim,
 * and a caller wanting {@code X80.00} runs it through {@code GTokenizer} rather than a second, looser
 * reader of the same bytes.
 *
 * <p>Everything is "the last thing the machine said" - absent rather than defaulted - except
 * {@link #invalidated()}, documented at its own declaration.
 *
 * <h2>Concurrency</h2>
 * One immutable {@link State} behind one volatile reference ({@link SdCardControl}'s pattern): the
 * aggregate's own thread is the only writer (every {@code on} runs on {@code PrinterState.onEvent}'s
 * {@code p3d-printer-<uuid>} thread), any thread may read ({@link #snapshot()} runs on whatever
 * subscribes to {@code PrinterReactor.updates()}), and the volatile write of a fully-built
 * {@code State} is the happens-before edge - a reader sees the whole of the old state or the whole
 * of the new one, never a list mid-rebuild. A synchronized mutable map would put a lock on the
 * aggregate's thread for a reader's benefit and would still need holding for the length of a
 * snapshot.
 */
public class EEPROMSettingsControl {

    /**
     * The settings as known, all three parts together.
     *
     * @param report    the dump in the order the firmware wrote it, sections and values interleaved
     * @param eeprom    {@code M504}'s last verdict, or null until one has answered
     * @param refusal   the {@code echo:Unknown command: "M503"} that proved the report absent, or
     *                  null while it is assumed present
     */
    record State(List<SettingsRs<?>> report, EepromReport eeprom, UnknownCommand refusal) {

        static final State NOTHING = new State(List.of(), null, null);
    }

    private volatile State state = State.NOTHING;

    State state() {
        return state;
    }

    /** One entry point for the whole family, so {@code PrinterState} has one arm - a reply added to it in {@code :gcode} arrives here already routed. */
    public void on(SettingsRs<?> reply) {
        switch (reply) {
            case SettingsSection section -> onSection(section);
            case SettingsValue value -> onValue(value);
            case EepromReport report -> onEeprom(report);
            default -> {
                // a member of the family this control keeps nothing about; none exists today, the
                // arm exists because SettingsRs is an interface and not a sealed one.
            }
        }
    }

    /** A heading, appended unless the report already carries one with that title. */
    private void onSection(SettingsSection section) {
        var s = state;
        for (var held : s.report()) {
            if (held instanceof SettingsSection existing && existing.getTitle().equals(section.getTitle())) {
                return;
            }
        }
        var report = new ArrayList<>(s.report());
        report.add(section);
        state = new State(List.copyOf(report), s.eeprom(), s.refusal());
    }

    /** A setting, replacing the one held for the same key in its own place, or appended. */
    private void onValue(SettingsValue value) {
        var s = state;
        var key = key(value);
        var report = new ArrayList<>(s.report());
        for (int i = 0; i < report.size(); i++) {
            if (report.get(i) instanceof SettingsValue held && key(held).equals(key)) {
                report.set(i, value);
                state = new State(List.copyOf(report), s.eeprom(), s.refusal());
                return;
            }
        }
        report.add(value);
        state = new State(List.copyOf(report), s.eeprom(), s.refusal());
    }

    /** {@code M504}'s verdict, replacing whatever the last one said. */
    private void onEeprom(EepromReport report) {
        var s = state;
        state = new State(s.report(), report, s.refusal());
    }

    /** The code a setting is reported under - {@code M145} excepted. See {@link #INDEXED}. */
    private static String key(SettingsValue value) {
        if (!INDEXED.contains(value.getCode())) {
            return value.getCode();
        }
        var index = indexWord(value.getCommand());
        return index == null ? value.getCode() : value.getCode() + " S" + index;
    }

    /**
     * Codes whose {@code S} word is part of their identity - an exception list, not a rule, because
     * every general rule guesses wrong somewhere ({@code M200 S0 D1.75}'s {@code S} is a flag, not
     * an index; "reported twice" cannot be evaluated until the dump has finished arriving).
     * {@code M145} is the only code MarlinSim reports twice in one dump - the two material presets -
     * measured:
     * <pre>
     * echo:  M145 S0 H180.00 B70.00 F0
     * echo:  M145 S1 H240.00 B110.00 F0
     * </pre>
     * An indexed setting not named here merges wrongly and loses all but its last instance; add the
     * code here when one turns up, with the dump that proves it.
     */
    private static final List<String> INDEXED = List.of("M145");

    /**
     * The {@code S} word's lexeme, read with {@code :gcode}'s own tokenizer rather than a split on
     * spaces - spec sect. 2.1 already defines how a letter pairs with a number.
     *
     * @return the lexeme as the firmware wrote it, or null when there is no {@code S} word
     */
    private static String indexWord(String command) {
        var tokens = GTokenizer.INSTANCE.parse((CharSequence) command).iterator();
        boolean afterS = false;
        while (tokens.hasNext()) {
            var token = tokens.next();
            if (token instanceof GIdentifier identifier) {
                afterS = identifier.isChar('S');
            } else if (token instanceof GNumber number) {
                if (afterS) {
                    return number.getLexeme();
                }
                afterS = false;
            }
        }
        return null;
    }

    /**
     * {@code echo:Unknown command: "…"} - the only way to prove this firmware has no {@code M503}.
     * {@code Cap:EEPROM:1}/{@code MachineProfile.hasEeprom()} say the firmware stores settings;
     * {@code DISABLE_M503} removes only the report, with no capability line announcing it - so the
     * report is assumed present until refused. Other refusals are ignored: {@code UnknownCommand}
     * answers anything unrecognised, and only {@code M503}'s is about settings.
     *
     * @return whether this changed anything - true only for the first {@code M503} refusal of a session
     */
    public boolean on(UnknownCommand refusal) {
        if (!M503.equals(refusal.getCommand())) {
            return false;
        }
        var s = state;
        if (s.refusal() != null) {
            return false;
        }
        state = new State(s.report(), s.eeprom(), refusal);
        return true;
    }

    /** The command whose refusal this control is about. */
    private static final String M503 = "M503";

    /** Whether this firmware is believed to answer {@code M503}. See {@link #on(UnknownCommand)}. */
    public boolean reportSupported() {
        return state.refusal() == null;
    }

    /**
     * An {@code M501} or {@code M502} was sent, so the held report no longer matches the machine.
     * Both answer a bare {@code ok} with no announcement while changing live memory - measured
     * against {@code M503} as the control:
     * <pre>
     * M503 -&gt; M104 S0 B250 F0.00    (stored)
     * M502 -&gt; ok
     * M503 -&gt; M104 S210 B250 F0.10  (defaults, unannounced)
     * M501 -&gt; ok
     * M503 -&gt; M104 S0 B250 F0.00    (back)
     * </pre>
     * So this forgets the report; {@code PrinterState.handle(RestoreSettings)} re-asks rather than
     * inventing a "stale" event. {@link #reportSupported()} and {@code M504}'s verdict are
     * deliberately untouched: neither command changes which commands the firmware understands, and
     * neither writes the EEPROM (the transcript proves it - {@code M501} could not restore values
     * that were not still there), so {@code M504}'s judgement of the stored contents stays true.
     * Like {@link SdCardControl#released()}, this follows a command rather than a reply, because
     * there is no reply to follow.
     */
    public void invalidated() {
        var s = state;
        state = new State(List.of(), s.eeprom(), s.refusal());
    }

    /**
     * A new session: everything known belongs to the last one, including
     * {@link #reportSupported()} - a link that was down may return to a reflashed machine, or one
     * swapped at the same address (see {@link SdCardControl#sessionStarted()}), and a remembered
     * refusal would hide the tab forever on a firmware that now answers {@code M503} fine.
     * Announced to whoever is watching by {@code PrinterState.onOnline}, as a
     * {@code SessionStarted}.
     */
    public void sessionStarted() {
        state = State.NOTHING;
    }

    /**
     * The settings as the replies that announced them: the report in firmware order, then
     * {@code M504}'s verdict if any, then the refusal if {@code M503} was declined - each published
     * as itself rather than a synthetic "unsupported" event, so a late page learns it the same way
     * the first one did.
     */
    public List<Object> snapshot() {
        var s = state;
        var replies = new ArrayList<Object>(s.report());
        if (s.eeprom() != null) {
            replies.add(s.eeprom());
        }
        if (s.refusal() != null) {
            replies.add(s.refusal());
        }
        return replies;
    }
}
