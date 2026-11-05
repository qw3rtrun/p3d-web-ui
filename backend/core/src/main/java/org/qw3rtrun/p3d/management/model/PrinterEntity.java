package org.qw3rtrun.p3d.management.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OrderColumn;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.BedVolume;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class PrinterEntity {

    @Id
    UUID id;
    @NonNull
    String name;
    @NonNull
    String host;
    int port;

    /**
     * The roster's record of where this machine's connection was, last time anything said. The
     * state, not a {@code connected} boolean: the column it replaces was write-only (nothing but a
     * test ever called {@code MachineManagementService.connect}/{@code disconnect}, since the
     * connect endpoint has always gone to the runtime instead), so every row said {@code false} from
     * registration onwards - two truths that could not agree, which plan item B3 set out to end.
     * Written from {@code MConnectionChangedEvent} and nowhere else - only {@code core} can know
     * this - and read once, at startup, to decide which machines to dial again;
     * {@link #isConnected()} is derived from it. A string, not an ordinal, so inserting a constant
     * into the enum cannot silently re-point every stored row.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "connection_state", length = 32)
    MachineConnectionState connectionState = MachineConnectionState.OFFLINE;

    /** What {@code M115} reported, key by key, exactly as written - a table, not a JSON blob, since the keys differ between firmware builds and this module must not parse one. {@code EAGER}: a {@code Descriptor} always carries the profile, and the farm is tens of rows. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "printer_firmware", joinColumns = @JoinColumn(name = "printer_id"))
    @MapKeyColumn(name = "key_name")
    @Column(name = "key_value")
    Map<String, String> firmware = new HashMap<>();

    /** Each {@code Cap:} line the machine declared, by name. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "printer_capability", joinColumns = @JoinColumn(name = "printer_id"))
    @MapKeyColumn(name = "capability")
    @Column(name = "enabled")
    Map<String, Boolean> capabilities = new HashMap<>();

    /** When a machine last told us any of the above - a cache of something only the machine knows, which can go stale (a reflash while the farm was down), so a reader can judge rather than assume. */
    LocalDateTime profileLearnedAt;

    /**
     * What a human declared, kept in columns of its own and never written by a firmware report -
     * plan item B4's "what was discovered is distinguishable from what was typed" expressed in the
     * schema, since {@link #learnedFirmware} rewrites the profile wholesale and must not be able to
     * touch these. Boxed because null is a third state: a bed of zero is not a bed, and a declared
     * extruder count of zero would refuse every command rather than deferring to {@code M115}.
     */
    @Column(name = "bed_x")
    Integer bedX;
    @Column(name = "bed_y")
    Integer bedY;
    @Column(name = "bed_z")
    Integer bedZ;

    @Column(name = "declared_extruder_count")
    Integer declaredExtruderCount;

    /** One nozzle diameter per tool, in tool order - {@code @OrderColumn}, not a set, since tool order is what a G-code file's {@code T} commands mean. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "printer_nozzle", joinColumns = @JoinColumn(name = "printer_id"))
    @OrderColumn(name = "tool_index")
    @Column(name = "diameter")
    List<Double> nozzleDiameters = new ArrayList<>();

    /** What this machine is set up to print. Unordered, because no material is the first one. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "printer_material", joinColumns = @JoinColumn(name = "printer_id"))
    @Column(name = "material")
    Set<String> materials = new HashSet<>();

    /** Free-form labels, which is also how this farm expresses groups - see {@link MachineSpec}. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "printer_tag", joinColumns = @JoinColumn(name = "printer_id"))
    @Column(name = "tag")
    Set<String> tags = new HashSet<>();

    /** A machine as it is registered: reachable, and nothing known about it yet. */
    public PrinterEntity(UUID id, String name, String host, int port) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.connectionState = MachineConnectionState.OFFLINE;
    }

    /** Where the machine's connection was, last time {@code core} said. Never null. */
    public MachineConnectionState connectionState() {
        return connectionState == null ? MachineConnectionState.OFFLINE : connectionState;
    }

    /** Whether the roster believes there is a connection - derived, not stored, since a second persisted field saying the same thing is a second thing that can be wrong (as the one this replaced permanently was). */
    public boolean isConnected() {
        return connectionState().isLinked();
    }

    /** Records what {@code core} reported about the connection - not announced back, unlike firmware/capability reports, since this fact came from {@code core} in the first place and handing it back would only loop. */
    public void connectionChanged(MachineConnectionState state) {
        this.connectionState = state == null ? MachineConnectionState.OFFLINE : state;
    }

    /** What this machine turned out to be, or {@link MachineProfile#unknown()} if nothing ever asked. */
    public MachineProfile profile() {
        return profileLearnedAt == null
                ? MachineProfile.unknown()
                : new MachineProfile(Map.copyOf(firmware), Map.copyOf(capabilities), profileLearnedAt);
    }

    /** What a human declared about this machine, or {@link MachineSpec#undeclared()} if nobody has. */
    public MachineSpec spec() {
        return new MachineSpec(bedVolume(), declaredExtruderCount,
                List.copyOf(nozzleDiameters), Set.copyOf(materials), Set.copyOf(tags));
    }

    /**
     * Records what a human declared, replacing whatever was there - unlike {@link #learnedCapability},
     * because a person submits a whole form and a material removed from it is meant to be gone (a
     * firmware instead sends capabilities one at a time with no moment the set is complete).
     * Collections are cleared and refilled rather than reassigned, since Hibernate tracks the
     * instance it handed out and a new one orphans the old, throwing on flush.
     */
    public void declare(MachineSpec spec) {
        var declared = spec == null ? MachineSpec.undeclared() : spec;
        var bed = declared.bedVolume();
        bedX = bed == null ? null : bed.x();
        bedY = bed == null ? null : bed.y();
        bedZ = bed == null ? null : bed.z();
        declaredExtruderCount = declared.extruderCount();
        nozzleDiameters.clear();
        nozzleDiameters.addAll(declared.nozzleDiameters());
        materials.clear();
        materials.addAll(declared.materials());
        tags.clear();
        tags.addAll(declared.tags());
    }

    /** Renames the machine and re-points it, which is the rest of what an edit may change. */
    public void reachableAs(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    /** A bed is all three dimensions or none: a partly-filled form declared nothing. */
    private BedVolume bedVolume() {
        if (bedX == null || bedY == null || bedZ == null) {
            return null;
        }
        return new BedVolume(bedX, bedY, bedZ);
    }

    /** Records what {@code M115} said, replacing whatever was there - a firmware report is a complete statement about one build, so a key that stopped being reported has stopped being true. */
    public void learnedFirmware(Map<String, String> reported, LocalDateTime when) {
        firmware.clear();
        firmware.putAll(reported);
        profileLearnedAt = when;
    }

    /** Records one capability, leaving the others alone - upserted, since the firmware sends these one line at a time with no moment the set is known complete. */
    public void learnedCapability(String capability, boolean enabled, LocalDateTime when) {
        capabilities.put(capability, enabled);
        profileLearnedAt = when;
    }
}
