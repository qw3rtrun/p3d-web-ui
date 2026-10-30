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
 * <p>These are the two questions the Storage tab is rendered from, and they are <strong>not the
 * same question</strong>: {@link MachineProfile#hasSdCard()} says whether the firmware has a card
 * reader, and {@link MachineProfile#isKnown()} says whether anything has ever asked. A tab can be
 * disabled for either reason and an operator needs to be told which, so a page that collapsed them
 * into one boolean would say "no SD card support" to somebody whose machine is merely unplugged.
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
     * A machine that has reported but never mentioned the capability has none, as far as this goes.
     *
     * <p>Mainline Marlin always writes the line, so in practice this is a build that predates it or
     * is not Marlin at all - and offering a card tab to a firmware that will not answer {@code M20}
     * is worse than not offering one.
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
     * Reported-with-no-reader and never-reported are both "no card", and tell apart on
     * {@code isKnown}.
     *
     * <p>Stated as its own claim because it is the whole of the three-state rule in
     * {@code monitor.html}: the tab is enabled on {@code hasSdCard}, and when it is not, which of
     * the two sentences the tooltip carries is decided by {@code isKnown}.
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
