package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.event.EepromReport;
import org.qw3rtrun.p3d.g.marlin.event.SettingsRs;
import org.qw3rtrun.p3d.g.marlin.event.SettingsSection;
import org.qw3rtrun.p3d.g.marlin.event.SettingsValue;
import org.qw3rtrun.p3d.g.marlin.event.UnknownCommand;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the machine said its settings are, as the replies that would have announced it.
 */
class EEPROMSettingsControlTest {

    /**
     * The dump MarlinSim writes, captured verbatim on 2026-11-04 from
     * {@code FIRMWARE_NAME:Marlin bugfix-2.1.x (Oct 29 2026 10:28:56) MACHINE_TYPE:MarlinSim}.
     *
     * <p>Kept as the firmware's own text, headings and all, because the headings are what the tab
     * groups by and the awkward ones - brackets, angle brackets, a trailing comment on
     * {@code G21} - are exactly what must survive being held.
     */
    private static final List<Object[]> DUMP = List.of(
            new Object[]{"Linear Units", "G21", "G21 ; (mm)"},
            new Object[]{"Auto Temp Control", "M104", "M104 S0 B250 F0.00"},
            new Object[]{"Temperature Units", "M149", "M149 C ; Units in Celsius"},
            new Object[]{"Filament settings (Disabled)", "M200", "M200 S0 D1.75"},
            new Object[]{"Steps per unit", "M92", "M92 X80.00 Y80.00 Z400.00 E500.00"},
            new Object[]{"Max feedrates (units/s)", "M203", "M203 X300.00 Y300.00 Z5.00 E25.00"},
            new Object[]{"Max Acceleration (units/s2)", "M201", "M201 X3000.00 Y3000.00 Z100.00 E10000.00"},
            new Object[]{"Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>)",
                    "M204", "M204 P3000.00 R3000.00 T3000.00"},
            new Object[]{"Advanced (B<min_segment_time_us> S<min_feedrate> T<min_travel_feedrate> J<junc_dev>)",
                    "M205", "M205 B20000.00 S0.00 T0.00 J0.01"},
            new Object[]{"Home offset", "M206", "M206 X0.00 Y0.00 Z0.00"},
            new Object[]{"Material heatup parameters", "M145", "M145 S0 H180.00 B70.00 F0"},
            new Object[]{null, "M145", "M145 S1 H240.00 B110.00 F0"},
            new Object[]{"Hotend PID", "M301", "M301 P22.20 I1.08 D114.00"});

    /** The dump as the replies the transport would have decoded it into, in the order it arrives. */
    private static List<SettingsRs<?>> dump() {
        var lines = new ArrayList<SettingsRs<?>>();
        for (var line : DUMP) {
            if (line[0] != null) {
                lines.add(new SettingsSection((String) line[0]));
            }
            lines.add(new SettingsValue((String) line[1], (String) line[2]));
        }
        return lines;
    }

    private final EEPROMSettingsControl settings = new EEPROMSettingsControl();

    private void fold(List<SettingsRs<?>> lines) {
        lines.forEach(settings::on);
    }

    /** Nothing said is nothing known: an unasked machine has no settings, not empty ones. */
    @Test
    void aMachineNobodyAskedHasNoReport() {
        assertEquals(List.of(), settings.snapshot());
    }

    /**
     * The whole dump comes back in report order, sections and values interleaved as received.
     *
     * <p><strong>One ordered list and not three maps.</strong> The sections are what the tab groups
     * by, so a value's place in the sequence is the only thing that says which heading it belongs
     * under - the firmware writes no other association.
     */
    @Test
    void theWholeDumpReplaysInReportOrder() {
        fold(dump());

        assertEquals(List.copyOf(dump()), settings.snapshot());
    }

    /**
     * A second value for a code replaces the first <em>in place</em>.
     *
     * <p>This is what makes a single-setting read-back useful: {@code >> M92} answers
     * {@code <<   M92 X81.00 …} in the unprefixed shape and nothing else, so the held report has to
     * absorb one line without a second full dump - and without moving it out from under its own
     * heading. Asserted as the whole sequence rather than "the new value is in there", because
     * appending would satisfy the weaker claim and put the setting under the wrong section.
     */
    @Test
    void aSecondValueForACodeReplacesTheFirstInItsPlace() {
        fold(dump());
        var readBack = new SettingsValue("M92", "M92 X81.00 Y80.00 Z400.00 E500.00");

        settings.on(readBack);

        var expected = new ArrayList<Object>(dump());
        expected.set(expected.indexOf(new SettingsValue("M92", "M92 X80.00 Y80.00 Z400.00 E500.00")),
                readBack);
        assertEquals(expected, settings.snapshot());
    }

    /**
     * Both {@code M145} presets survive a re-read, which is the test for the named exception.
     *
     * <p><strong>{@code M145} is the one code MarlinSim reports twice in a single dump</strong> -
     * {@code S0} and {@code S1}, the two material presets - so a merge keyed on the code alone
     * collapses them into one and the tab loses a preset. The exception is named rather than
     * general on purpose: {@code M200 S0 D1.75} also carries an {@code S} and is reported once, so
     * every general rule tried guesses wrong somewhere.
     *
     * <p>Folded twice, because once is what the order test already covers. If this passes with the
     * exception removed, the test is wrong.
     */
    @Test
    void bothMaterialPresetsSurviveAReread() {
        fold(dump());
        fold(dump());

        var presets = settings.snapshot().stream()
                .filter(SettingsValue.class::isInstance)
                .map(SettingsValue.class::cast)
                .filter(value -> value.getCode().equals("M145"))
                .map(SettingsValue::getCommand)
                .toList();

        assertEquals(List.of("M145 S0 H180.00 B70.00 F0", "M145 S1 H240.00 B110.00 F0"), presets,
                "keying on M145 alone collapses the two presets into one");
    }

    /**
     * A re-read is the same report, not twice the report.
     *
     * <p>A section whose title is already held is not appended again, and neither is a value whose
     * code is - so pressing refresh twice leaves the tab exactly as it was rather than doubling
     * every row.
     */
    @Test
    void aRereadDoesNotDuplicateAnything() {
        fold(dump());
        var once = settings.snapshot();

        fold(dump());

        assertEquals(once, settings.snapshot());
    }

    // --------------------------------------------------------------------------- M504's verdict

    /**
     * {@code M504}'s answer is held and replayed, as the string the firmware wrote.
     *
     * <p>Not reduced to a boolean: only the healthy answer has been measured - MarlinSim writes
     * {@code echo:EEPROM OK} and has no way to be asked for a corrupt one - so a boolean would have
     * meant inventing the failure text and a rule for recognising it.
     */
    @Test
    void theEepromVerdictIsHeldAndReplayed() {
        settings.on(new EepromReport("OK"));

        assertEquals(List.of(new EepromReport("OK")), settings.snapshot());
    }

    /** It sits after the report, so a tab reads its rows before the footer that judges them. */
    @Test
    void theVerdictFollowsTheReport() {
        fold(dump());
        settings.on(new EepromReport("OK"));

        var snapshot = settings.snapshot();

        assertEquals(new EepromReport("OK"), snapshot.getLast());
        assertEquals(List.copyOf(dump()), snapshot.subList(0, snapshot.size() - 1));
    }

    // ------------------------------------------------------------- whether M503 exists at all

    /** Assumed present until a machine says otherwise, because mainline Marlin has it. */
    @Test
    void theReportIsAssumedToExistUntilItIsRefused() {
        assertTrue(settings.reportSupported());
    }

    /**
     * An {@code M503} refusal turns it to no, once.
     *
     * <p>{@code DISABLE_M503} is a real build option, and a firmware compiled with it stores
     * settings perfectly well and simply cannot report them - so {@code Cap:EEPROM:1} is true and
     * the report is still absent. The only thing that can tell a host is the refusal, and the return
     * value says whether anything changed so the aggregate knows whether it has news.
     */
    @Test
    void aRefusalOfM503SaysTheReportIsNotThere() {
        assertTrue(settings.on(new UnknownCommand("M503")), "the first one is news");
        assertFalse(settings.reportSupported());

        assertFalse(settings.on(new UnknownCommand("M503")), "the second one is not");
        assertFalse(settings.reportSupported());
    }

    /** A refusal of some other command says nothing about {@code M503}. */
    @Test
    void aRefusalOfSomeOtherCommandChangesNothing() {
        fold(dump());
        var before = settings.snapshot();

        assertFalse(settings.on(new UnknownCommand("M851")));

        assertTrue(settings.reportSupported());
        assertEquals(before, settings.snapshot());
    }

    /**
     * The refusal itself is what a late subscriber is handed, and that is deliberate.
     *
     * <p>The thing that announced the fact is the thing that gets published, so the farm invents no
     * "settings unsupported" event of its own and a page that opens afterwards learns it the same
     * way the first one did - which is the rule the rest of {@link EEPROMSettingsControl#snapshot()}
     * already keeps.
     */
    @Test
    void theRefusalIsPartOfTheSnapshotOnceM503IsKnownAbsent() {
        settings.on(new UnknownCommand("M503"));

        assertEquals(List.of(new UnknownCommand("M503")), settings.snapshot());
    }

    /** With a report, a verdict and a refusal all held, the order is report, verdict, refusal. */
    @Test
    void theSnapshotIsReportThenVerdictThenRefusal() {
        fold(dump());
        settings.on(new EepromReport("OK"));
        settings.on(new UnknownCommand("M503"));

        var snapshot = settings.snapshot();

        assertEquals(new UnknownCommand("M503"), snapshot.getLast());
        assertEquals(new EepromReport("OK"), snapshot.get(snapshot.size() - 2));
    }

    // ----------------------------------------------------------------- forgetting, two ways

    /**
     * A new session starts from nothing known, <strong>including</strong> that {@code M503} works.
     *
     * <p>What comes back up on the same address can be a different firmware: a reflash, a cable
     * moved, a board swapped. {@link SdCardControl#sessionStarted()} makes the same argument for a
     * card that could have been swapped while the link was down, and a refusal remembered across a
     * session would hide a Settings tab on a machine that now answers perfectly well.
     */
    @Test
    void aNewSessionForgetsEverythingIncludingTheRefusal() {
        fold(dump());
        settings.on(new EepromReport("OK"));
        settings.on(new UnknownCommand("M503"));

        settings.sessionStarted();

        assertEquals(List.of(), settings.snapshot());
        assertTrue(settings.reportSupported(), "a reflashed machine may well answer M503 now");
    }

    /**
     * {@code M501}/{@code M502} empty the report and leave {@code reportSupported} alone.
     *
     * <p>The two forgettings are different facts. A restore or a reset changed what the settings
     * <em>are</em> - and said nothing about it, which is why the held report has to go - but the
     * firmware is the same firmware it was a millisecond ago, so whether it can report is unchanged.
     * Forgetting that too would cost an {@code M503} that is known to be refused being sent again
     * after every reset.
     */
    @Test
    void anInvalidationEmptiesTheReportAndKeepsWhetherM503Works() {
        settings.on(new UnknownCommand("M503"));
        fold(dump());

        settings.invalidated();

        assertFalse(settings.reportSupported(), "M501 did not reflash the machine");
        assertEquals(List.of(new UnknownCommand("M503")), settings.snapshot(),
                "the report is gone; what announced the refusal is not");
    }

    /**
     * And it leaves {@code M504}'s verdict alone, which is a deliberate departure from "empty
     * everything".
     *
     * <p><strong>{@code M501} and {@code M502} do not write the EEPROM.</strong> The control run
     * this feature was designed from proves it from both ends: {@code M502} changed live memory
     * ({@code M104 S0 B250 F0.00} became {@code M104 S210 B250 F0.10}) and {@code M501} put the
     * stored values back, which it could only do because they were still there. {@code M504} judges
     * the <em>stored</em> contents, so its verdict is as true after a restore or a reset as it was
     * before and discarding it would make the tab ask again for an answer it already has.
     */
    @Test
    void anInvalidationKeepsTheEepromVerdict() {
        settings.on(new EepromReport("OK"));
        fold(dump());

        settings.invalidated();

        assertEquals(List.of(new EepromReport("OK")), settings.snapshot(),
                "M501 and M502 change live memory only; the stored contents are untouched");
    }
}
