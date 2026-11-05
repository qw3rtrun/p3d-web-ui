package org.qw3rtrun.p3d.api.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a machine turned out to be, as the pages ask it.
 *
 * <p>Two different questions the Storage tab is rendered from: {@link MachineProfile#hasSdCard()}
 * says whether the firmware has a card reader, {@link MachineProfile#isKnown()} says whether
 * anything has ever asked - collapsing them would say "no SD card support" to an unplugged machine.
 */
class MachineProfileTest {

    private static MachineProfile reported(Map<String, Boolean> capabilities) {
        return new MachineProfile(Map.of(), capabilities, LocalDateTime.now());
    }

    @Test
    void aFirmwareReportingACardReaderHasOne() {
        assertTrue(reported(Map.of("SDCARD", true)).hasSdCard());
    }

    @Test
    void aFirmwareReportingNoCardReaderHasNone() {
        assertFalse(reported(Map.of("SDCARD", false)).hasSdCard());
    }

    /**
     * A machine that has reported but never mentioned the capability has none, as far as this
     * goes - mainline Marlin always writes the line, so offering a card tab here is worse than not.
     */
    @Test
    void anUnmentionedCapabilityIsNotACardReader() {
        assertFalse(reported(Map.of("EEPROM", true)).hasSdCard());
    }

    /**
     * A machine nothing has ever reached has no card reader <em>and</em> is not known, which is the
     * distinction the tab's two tooltips are built on.
     */
    @Test
    void aMachineNothingHasAskedIsNotKnownAndHasNoCard() {
        var unknown = MachineProfile.unknown();

        assertFalse(unknown.hasSdCard());
        assertFalse(unknown.isKnown(), "the page must be able to say 'not reported yet' instead");
    }

    /**
     * Reported-with-no-reader and never-reported are both "no card", told apart by
     * {@code isKnown} - the whole of the three-state rule {@code monitor.html}'s tooltip is built on.
     */
    @Test
    void theTwoReasonsForNoCardAreToldApartByWhetherAnythingReported() {
        var noReader = reported(Map.of("SDCARD", false));
        var neverAsked = MachineProfile.unknown();

        assertFalse(noReader.hasSdCard());
        assertFalse(neverAsked.hasSdCard());

        assertTrue(noReader.isKnown(), "this machine answered, and the answer was no");
        assertFalse(neverAsked.isKnown(), "this one never answered at all");
    }

    // ------------------------------------------------------------------------------ the EEPROM

    /**
     * The capability is spelled {@code EEPROM} on the wire - the whole point of this test. Issue
     * #88's checklist calls it {@code EEPROM_SETTINGS}, Marlin's build macro, which never leaves
     * the compiler; what a machine writes is {@code Cap:EEPROM:1}, measured off MarlinSim among
     * its 38 {@code Cap:} lines and cross-checked against
     * {@code Marlin/src/gcode/host/M115.cpp} on {@code 2.1.x}.
     */
    @Test
    void aFirmwareReportingAnEepromHasOne() {
        assertTrue(reported(Map.of("EEPROM", true)).hasEeprom());
    }

    @Test
    void aFirmwareReportingNoEepromHasNone() {
        assertFalse(reported(Map.of("EEPROM", false)).hasEeprom());
    }

    /** The build macro is not the capability, so a profile carrying only it answers no. */
    @Test
    void theBuildMacroNameIsNotTheCapability() {
        assertFalse(reported(Map.of("EEPROM_SETTINGS", true)).hasEeprom(),
                "EEPROM_SETTINGS is the compile-time flag; Cap:EEPROM is what reaches the wire");
    }

    /** An EEPROM and a card reader are separate questions, so neither answers for the other. */
    @Test
    void theEepromAndTheCardAreSeparateCapabilities() {
        var cardOnly = reported(Map.of("SDCARD", true));
        var eepromOnly = reported(Map.of("EEPROM", true));

        assertFalse(cardOnly.hasEeprom());
        assertFalse(eepromOnly.hasSdCard());
    }

    /**
     * A machine nothing has ever reached has no EEPROM <em>and</em> is not known - the same
     * three-state rule the Storage tab keeps, now for the Settings tab.
     */
    @Test
    void aMachineNothingHasAskedIsNotKnownAndHasNoEeprom() {
        var unknown = MachineProfile.unknown();

        assertFalse(unknown.hasEeprom());
        assertFalse(unknown.isKnown(), "the page must be able to say 'not reported yet' instead");
    }

    /** The capability map is the firmware's own, so an unusual key is carried rather than dropped. */
    @Test
    void theCapabilityMapIsKeptAsReported() {
        var capabilities = new LinkedHashMap<String, Boolean>();
        capabilities.put("SDCARD", true);
        capabilities.put("SOMETHING_NEW", true);

        var profile = reported(capabilities);

        assertTrue(profile.hasSdCard());
        assertEquals(2, profile.capabilities().size());
    }
}
