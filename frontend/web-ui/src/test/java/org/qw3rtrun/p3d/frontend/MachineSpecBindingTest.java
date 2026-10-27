package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.BedVolume;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The JSON the management API carries, in both directions (plan item B4).
 *
 * <p>Pinned here for the reason {@code PrinterCommandBindingTest} exists: the browser is not
 * compiled by this build, so a field that stops binding fails nowhere. This is also the surface a
 * farm is set up through, which makes a silently-dropped bed size a worse failure than most - the
 * machine would simply never be matched to a job it could print.
 *
 * <p><strong>The assertion that matters most is the last one.</strong> B4's "what was discovered is
 * distinguishable from what was typed" has to hold at the edge as well as in the schema: there must
 * be no request that makes the farm claim a firmware said something it did not.
 */
class MachineSpecBindingTest {

    private final UUID id = UUID.randomUUID();
    private final AtomicReference<MachineSpec> declared = new AtomicReference<>();
    private final AtomicReference<String> renamedTo = new AtomicReference<>();

    private static final MachineProfile REPORTED =
            new MachineProfile(Map.of("FIRMWARE_NAME", "Marlin 2.1.2", "EXTRUDER_COUNT", "1"),
                    Map.of("EEPROM", true), LocalDateTime.of(2026, 1, 1, 12, 0));

    private Descriptor described() {
        return new Descriptor(id, renamedTo.get() == null ? "a printer" : renamedTo.get(),
                new ConnectionDetails("localhost", 8099), MachineConnectionState.OFFLINE,
                REPORTED, declared.get() == null ? MachineSpec.undeclared() : declared.get());
    }

    private WebTestClient client() {
        MachineManagementService management = new MachineManagementService() {
            @Override
            public Descriptor fetch(UUID uuid) {
                return described();
            }

            @Override
            public Descriptor add(String name, ConnectionDetails connection, MachineSpec spec) {
                renamedTo.set(name);
                declared.set(spec);
                return described();
            }

            @Override
            public Descriptor update(UUID uuid, String name, ConnectionDetails connection, MachineSpec spec) {
                renamedTo.set(name);
                declared.set(spec);
                return described();
            }

            @Override
            public Stream<Descriptor> fetchAll() {
                return Stream.of(described());
            }

            @Override
            public void delete(UUID uuid) {
            }
        };
        return WebTestClient.bindToController(new FDMPrinterManagementController(management)).build();
    }

    @Test
    void aCreateCarriesTheDeclaredSpec() {
        client().post().uri("/api-manager")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"a printer","host":"localhost","port":8099,
                         "spec":{"bedVolume":{"x":220,"y":220,"z":250},"extruderCount":2,
                                 "nozzleDiameters":[0.4,0.6],"materials":["PETG"],"tags":["prusa-shelf"]}}""")
                .exchange()
                .expectStatus().isOk();

        assertEquals(new BedVolume(220, 220, 250), declared.get().bedVolume());
        assertEquals(2, declared.get().extruderCount());
        assertEquals(List.of(0.4, 0.6), declared.get().nozzleDiameters());
        assertEquals(Set.of("PETG"), declared.get().materials());
        assertEquals(Set.of("prusa-shelf"), declared.get().tags());
    }

    /** Registering a printer you have just plugged in must not require knowing its bed size. */
    @Test
    void aCreateWithoutASpecDeclaresNothingRatherThanFailing() {
        client().post().uri("/api-manager")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"a printer","host":"localhost","port":8099}""")
                .exchange()
                .expectStatus().isOk();

        assertEquals(MachineSpec.undeclared(), declared.get());
    }

    @Test
    void aPutReplacesTheWholeDeclaration() {
        client().put().uri("/api-manager/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"renamed","host":"elsewhere","port":9000,
                         "spec":{"extruderCount":3,"tags":["loud"]}}""")
                .exchange()
                .expectStatus().isOk();

        assertEquals("renamed", renamedTo.get());
        assertEquals(3, declared.get().extruderCount());
        assertEquals(Set.of("loud"), declared.get().tags());
        assertNull(declared.get().bedVolume(), "a bed left out of a PUT is a bed cleared");
        assertEquals(List.of(), declared.get().nozzleDiameters());
    }

    /** What the farm reports back carries both halves, separately. */
    @Test
    void aFetchCarriesWhatWasReportedAndWhatWasDeclaredApart() {
        declared.set(new MachineSpec(null, 2, List.of(), Set.of(), Set.of()));

        client().get().uri("/api-manager/" + id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.spec.extruderCount").isEqualTo(2)
                .jsonPath("$.profile.firmware.EXTRUDER_COUNT").isEqualTo("1")
                .jsonPath("$.profile.firmware.FIRMWARE_NAME").isEqualTo("Marlin 2.1.2");
    }

    /**
     * There is no request that writes the profile.
     *
     * <p>A {@code profile} in the body is ignored rather than honoured: the payload has no such
     * field, so Jackson drops it. If this ever starts failing, somebody has added a way for a
     * browser to claim the firmware said something, and B4's whole distinction goes with it.
     */
    @Test
    void aRequestCannotDeclareWhatTheMachineReported() {
        client().put().uri("/api-manager/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"a printer","host":"localhost","port":8099,
                         "profile":{"firmware":{"FIRMWARE_NAME":"a lie"},"capabilities":{},
                                    "learnedAt":"2026-01-01T12:00:00"}}""")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.profile.firmware.FIRMWARE_NAME").isEqualTo("Marlin 2.1.2");
    }
}
