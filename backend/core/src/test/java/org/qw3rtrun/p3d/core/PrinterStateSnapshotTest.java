package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.BlocksAbandoned;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.PositionRs;
import org.qw3rtrun.p3d.g.marlin.event.SdCardOk;
import org.qw3rtrun.p3d.g.marlin.event.SdFile;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.g.marlin.event.SettingsSection;
import org.qw3rtrun.p3d.g.marlin.event.SettingsValue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * What the aggregate hands a subscriber that arrived late: everything it holds, as the events that
 * would have announced it, and nothing that merely happened.
 */
class PrinterStateSnapshotTest {

    private static final BareTemperatureRs WARM = new BareTemperatureRs(
            new HeaterReading(new BigDecimal("210.00"), new BigDecimal("210.00"), 127),
            null, null, null, null, null, null, Map.of());

    private static final SdFileList LISTING = new SdFileList(List.of(new SdFile("TEST.GCO", 47)));

    private static final SettingsSection STEPS = new SettingsSection("Steps per unit");

    private static final SettingsValue M92 = new SettingsValue("M92", "M92 X80.00 Y80.00 Z400.00 E500.00");

    @Test
    void aMachineNothingIsKnownAboutIsOnlyItsConnection() {
        var snapshot = new PrinterState(UUID.randomUUID()).snapshot();

        assertEquals(1, snapshot.size(), "no profile and no reading is absent, not empty: " + snapshot);
        assertInstanceOf(ConnectionStateChanged.class, snapshot.get(0));
    }

    /**
     * Connection first, then the profile - firmware, capabilities by name - then the card, then the
     * settings, then the temperature.
     *
     * <p>The order is the contract. A subscriber waits for the connection state to know it is
     * attached, and two snapshots of the same machine are the same sequence.
     */
    @Test
    void everythingHeldArrivesInAFixedOrder() {
        var printer = new PrinterState(UUID.randomUUID());
        printer.onEvent(WARM);
        printer.onEvent(new CapabilityRs("EEPROM", true));
        printer.onEvent(new FirmwareInfoRs(Map.of("FIRMWARE_NAME", "Marlin 2.1.2")));
        printer.onEvent(new CapabilityRs("AUTOREPORT_TEMP", true));
        printer.onEvent(LISTING);
        printer.onEvent(STEPS);
        printer.onEvent(M92);

        var snapshot = printer.snapshot();

        assertInstanceOf(ConnectionStateChanged.class, snapshot.get(0));
        assertEquals(List.of(
                new FirmwareInfoRs(Map.of("FIRMWARE_NAME", "Marlin 2.1.2")),
                new CapabilityRs("AUTOREPORT_TEMP", true),
                new CapabilityRs("EEPROM", true),
                SdCardOk.INSTANCE,
                LISTING,
                STEPS,
                M92,
                WARM), snapshot.subList(1, snapshot.size()));
    }

    /**
     * The settings sit between the card and the temperature, and keep the firmware's own order
     * inside that slot.
     *
     * <p>Asserted as its own test as well as inside the fixed-order one because the settings are a
     * <em>sequence</em> rather than a single value: everything else in the snapshot is one fact per
     * slot, and this slot is a 25-line report whose internal order is what a tab groups by.
     */
    @Test
    void theSettingsSitBetweenTheCardAndTheTemperature() {
        var printer = new PrinterState(UUID.randomUUID());
        printer.onEvent(WARM);
        printer.onEvent(LISTING);
        printer.onEvent(STEPS);
        printer.onEvent(M92);

        assertEquals(List.of(SdCardOk.INSTANCE, LISTING, STEPS, M92, WARM),
                printer.snapshot().subList(1, printer.snapshot().size()));
    }

    /**
     * A profile the roster seeded is held like one the machine reported, so a page opened after a
     * restart shows the firmware before anything has dialled the machine.
     */
    @Test
    void aSeededProfileIsPartOfTheSnapshot() {
        var seed = new MachineProfile(Map.of("FIRMWARE_NAME", "Marlin 2.1.2"), Map.of("EEPROM", false),
                LocalDateTime.now());
        var snapshot = new PrinterState(UUID.randomUUID(), seed, MachineSpec.undeclared()).snapshot();

        assertEquals(List.of(
                new FirmwareInfoRs(Map.of("FIRMWARE_NAME", "Marlin 2.1.2")),
                new CapabilityRs("EEPROM", false)), snapshot.subList(1, snapshot.size()));
    }

    /** Things that happened are not facts the aggregate holds, so a late subscriber is not told them. */
    @Test
    void whatHappenedIsNotHeld() {
        var printer = new PrinterState(UUID.randomUUID());
        printer.onEvent(new BlocksAbandoned(15, 3));
        printer.onEvent(new PositionRs(Map.of('X', new BigDecimal("10.00")), Map.of()));

        assertEquals(1, printer.snapshot().size(), "only the connection: " + printer.snapshot());
    }
}
