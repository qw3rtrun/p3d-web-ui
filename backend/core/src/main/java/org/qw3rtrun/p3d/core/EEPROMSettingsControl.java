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
 * What this machine says its own configuration is: {@code M503}'s report, {@code M504}'s verdict on
 * the EEPROM, and whether {@code M503} exists on this firmware at all.
 *
 * <p>The same shape as {@link SdCardControl}, {@link TemperatureControl} and
 * {@link ProfileControl}: {@code on} folds a reply in and {@link #snapshot()} hands the state back
 * as the replies that would have announced it, so a Settings tab opened late shows the machine's
 * settings instead of an empty table until somebody presses "read".
 *
 * <p><strong>The report is one ordered list, not three maps.</strong> The firmware interleaves
 * section headings with the values under them and writes no other association between the two, so a
 * value's place in the sequence <em>is</em> which heading it belongs under. Splitting it into
 * "sections" and "values" would throw that away and the tab groups by exactly it.
 *
 * <p><strong>Values merge in place; they do not accumulate.</strong> A second {@code SettingsValue}
 * for a code replaces the first and keeps its position, which is what makes a single-setting
 * read-back useful: {@code >> M92} answers one unprefixed line and nothing else, and the held report
 * absorbs it without a second 25-line dump and without moving the setting out from under its own
 * heading.
 *
 * <p><strong>Nothing here is parsed.</strong> The report <em>is</em> G-code and that is all it
 * claims to be - {@code :gcode}'s {@link SettingsValue} keeps the line verbatim, trailing comment
 * included, and a caller that wants {@code X80.00} runs it through {@code GTokenizer}, which
 * already reads exactly this syntax. A parsed settings model here would be a second place for the
 * number rules to drift.
 *
 * <p><strong>Everything is "the last thing the machine said", and only that</strong>, the rule every
 * control in this package keeps - with one exception, {@link #invalidated()}, whose reason is
 * written there. A setting nothing has reported is absent rather than defaulted: an unknown
 * configuration is not a blank one.
 *
 * <h2>Concurrency</h2>
 *
 * <p>One immutable {@link State} behind one volatile reference, which is {@link SdCardControl}'s
 * pattern and is why it is written that way. <strong>The aggregate's thread is the only writer</strong>
 * - every {@code on} here is reached from {@code PrinterState.onEvent}, which runs on that machine's
 * {@code p3d-printer-<uuid>} thread - and <strong>any thread may read</strong>, because
 * {@link #snapshot()} is called from whatever subscribes to {@code PrinterReactor.updates()}. The
 * volatile write of a fully-built {@code State} is the happens-before edge: a reader either sees the
 * previous report or the whole of the new one, never a list being rebuilt. The lists inside are
 * copied on every fold and handed out as {@code List.copyOf}, so nothing a reader holds can be
 * mutated underneath it.
 *
 * <p>A synchronized mutable map would be the wrong substitute twice over: it would put a lock on the
 * aggregate's thread for the benefit of a reader, and a reader iterating it would still have to hold
 * that lock for the length of a snapshot.
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

    /**
     * One line of a settings report, folded into what is known about them.
     *
     * <p>One entry point for the whole family, so {@code PrinterState} has one arm - the same
     * reason {@link SdCardControl#on} takes an {@code SdCardRs}. A reply added to the family in
     * {@code :gcode} arrives here already routed.
     */
    public void on(SettingsRs<?> reply) {
        switch (reply) {
            case SettingsSection section -> onSection(section);
            case SettingsValue value -> onValue(value);
            case EepromReport report -> onEeprom(report);
            default -> {
                // A member of the family this control has nothing to keep about. There is none
                // today; the arm exists because SettingsRs is an interface and not a sealed one.
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
     * The codes whose {@code S} word is part of their identity - a named exception, not a rule.
     *
     * <p><strong>{@code M145} is the only code MarlinSim reports twice in one dump</strong>, and it
     * was measured rather than reasoned about:
     *
     * <pre>
     * echo:  M145 S0 H180.00 B70.00 F0
     * echo:  M145 S1 H240.00 B110.00 F0
     * </pre>
     *
     * <p>Those are the two material presets, so keying on {@code M145} alone collapses them into
     * one and the tab loses a preset.
     *
     * <p><strong>It is a list and not a general rule because every general rule guesses wrong
     * somewhere.</strong> "A command with an {@code S} word is indexed" takes
     * {@code M200 S0 D1.75}, which is reported once and whose {@code S} is a flag rather than an
     * index. "A command reported twice is indexed" cannot be evaluated until the dump has finished
     * arriving, and a line is folded as it comes. So the trade is deliberate: an indexed setting
     * this list does not name merges wrongly and loses all but its last instance, and the fix is to
     * add the code here when one turns up - with the dump that proves it, the way this one is
     * proved.
     */
    private static final List<String> INDEXED = List.of("M145");

    /**
     * The {@code S} word's lexeme, read with {@code :gcode}'s own tokenizer.
     *
     * <p>The tokenizer and not a split on spaces, because the syntax rules for pairing a letter
     * with a number are spec 2.1's and are already written down once - and a key built by a second,
     * sloppier reading of the same bytes would disagree with them on something eventually.
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
     * {@code echo:Unknown command: "…"} - the only thing that can prove this firmware has no
     * {@code M503}.
     *
     * <p><strong>A third question, and neither of the other two answers it.</strong>
     * {@code Cap:EEPROM:1} says the firmware stores settings; {@code MachineProfile.hasEeprom()}
     * reads that. {@code DISABLE_M503} is a separate build option that removes the <em>report</em>
     * from a build which stores settings perfectly well, and no capability line announces it. So the
     * report is assumed present - mainline Marlin has it - until a machine refuses the command, and
     * then it is not.
     *
     * <p>Refusals of other commands are ignored. {@code UnknownCommand} is Marlin's answer to
     * anything it does not recognise, so this handler sees every one on the stream and only
     * {@code M503}'s is about the settings.
     *
     * @param refusal any {@code Unknown command:} reply
     * @return whether this changed anything, so the aggregate knows whether it has news. True only
     *         for the first {@code M503} refusal of a session
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
     * An {@code M501} or {@code M502} was sent, so the held report describes settings that are no
     * longer in the machine.
     *
     * <p><strong>This is the central design fact of the Settings tab.</strong> Both commands answer
     * a <em>bare {@code ok}</em> and announce nothing else - measured - while genuinely changing
     * live memory, with {@code M503} as the control:
     *
     * <pre>
     * &gt;&gt; M503   &lt;&lt; echo:  M104 S0 B250 F0.00      (from the EEPROM)
     * &gt;&gt; M502   &lt;&lt; ok P15 B3
     * &gt;&gt; M503   &lt;&lt; echo:  M104 S210 B250 F0.10    (the defaults - changed, and unannounced)
     * &gt;&gt; M501   &lt;&lt; ok P15 B3
     * &gt;&gt; M503   &lt;&lt; echo:  M104 S0 B250 F0.00      (back)
     * </pre>
     *
     * <p>So anything still holding the old report is silently wrong and the firmware will never
     * correct it. Forgetting is half the answer; the other half is {@code PrinterState}'s, which
     * re-asks - see {@code PrinterState.handle(RestoreSettings)} for why re-asking beat telling the
     * UI the report had gone stale.
     *
     * <p><strong>{@link #reportSupported()} is deliberately untouched.</strong> A restore or a reset
     * changed what the settings are; it did not change which commands the firmware understands.
     * Clearing it would send an {@code M503} that is known to be refused after every reset.
     *
     * <p><strong>And so is {@code M504}'s verdict</strong>, which is the less obvious half. Neither
     * {@code M501} nor {@code M502} writes the EEPROM - the transcript above proves it from both
     * ends, because {@code M501} could only put the stored values back if they were still there -
     * and {@code M504} judges the stored contents. Its answer is as true afterwards as before.
     *
     * <p>Like {@link SdCardControl#released()}, this is the one part of the class that follows a
     * command rather than a reply, and for the same kind of reason: there is no reply to follow.
     */
    public void invalidated() {
        var s = state;
        state = new State(List.of(), s.eeprom(), s.refusal());
    }

    /**
     * A new session on the wire: whatever was known about the settings belonged to the last one.
     *
     * <p>Everything goes, <strong>including {@link #reportSupported()}</strong>. A link that was
     * down may have been down because the machine was being reflashed, or because a cable was moved
     * to a different machine at the same address - {@link SdCardControl#sessionStarted()} makes the
     * same argument for a card that could have been swapped. A remembered refusal would hide the
     * Settings tab on a firmware that now answers {@code M503} perfectly well, and nothing would
     * ever ask again.
     */
    public void sessionStarted() {
        state = State.NOTHING;
    }

    /**
     * The settings as the replies that would have announced them: the report in the order the
     * firmware wrote it, then {@code M504}'s verdict if one has answered, then the refusal that
     * proved {@code M503} absent if one arrived.
     *
     * <p><strong>The refusal is published as itself, which is deliberate.</strong> The thing that
     * announced the fact is the thing that gets handed on, so the farm invents no
     * "settings-unsupported" event of its own and a page that opens later learns it exactly the way
     * the first one did. That is the rule the rest of this method already keeps - every element here
     * is a reply the machine actually sent.
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
