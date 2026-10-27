package org.qw3rtrun.p3d.management.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
     * The roster's record of where this machine's connection was, last time anything said.
     *
     * <p><strong>The state, not a {@code connected} boolean.</strong> The column it replaces was
     * write-only: {@code MachineManagementService.connect} and {@code disconnect} were called by
     * nothing but a test, because the connect endpoint has always gone to the runtime instead, so
     * every row said {@code false} from registration onwards. Two truths that could not agree,
     * which is what plan item B3 set out to end.
     *
     * <p>Written from {@code MConnectionChangedEvent} and from nowhere else - only {@code core}
     * can know this, because knowing it means holding the socket - and read once, at startup, to
     * decide which machines to dial again. {@link #isConnected()} is derived from it rather than
     * stored beside it.
     *
     * <p>A string and not an ordinal: an ordinal would silently re-point every stored row at a
     * different state the first time a constant is inserted into the enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "connection_state", length = 32)
    MachineConnectionState connectionState = MachineConnectionState.OFFLINE;

    /**
     * What {@code M115} reported, key by key, exactly as the firmware wrote it.
     *
     * <p>A table and not a JSON column: the keys differ between firmware builds, so a host that
     * modelled only the ones it knows today would drop whatever a later Marlin adds, and a blob
     * would mean this module parsing something. {@code EAGER} because a {@code Descriptor} always
     * carries the profile and the farm is tens of rows, not millions.
     */
    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "printer_firmware", joinColumns = @JoinColumn(name = "printer_id"))
    @MapKeyColumn(name = "key_name")
    @Column(name = "key_value")
    Map<String, String> firmware = new HashMap<>();

    /** Each {@code Cap:} line the machine declared, by name. */
    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "printer_capability", joinColumns = @JoinColumn(name = "printer_id"))
    @MapKeyColumn(name = "capability")
    @Column(name = "enabled")
    Map<String, Boolean> capabilities = new HashMap<>();

    /**
     * When a machine last told us any of the above.
     *
     * <p>The profile is a cache of something only the machine knows, so it can be stale - a printer
     * reflashed while the farm was down reports differently next time it connects. Recording when
     * it was learned is what lets a reader judge that, rather than assuming the row is current.
     */
    LocalDateTime profileLearnedAt;

    /**
     * What a human declared, kept in columns of its own and never written by a firmware report.
     *
     * <p>That separation is plan item B4's "what was discovered is distinguishable from what was
     * typed", expressed in the schema rather than with a flag per field. {@link #learnedFirmware}
     * rewrites the profile wholesale every time a machine reports; it must not be able to touch
     * these, and a column it cannot name is the cheapest possible guarantee of that.
     *
     * <p>Boxed, because null is a third state here and not a zero: a bed of zero is not a bed, and
     * a declared extruder count of zero would refuse every command rather than deferring to what
     * {@code M115} said.
     */
    @Column(name = "bed_x")
    Integer bedX;
    @Column(name = "bed_y")
    Integer bedY;
    @Column(name = "bed_z")
    Integer bedZ;

    @Column(name = "declared_extruder_count")
    Integer declaredExtruderCount;

    /**
     * One nozzle diameter per tool, in tool order.
     *
     * <p>An {@code @OrderColumn} and not a set: a nozzle belongs to a tool, and tool order is what
     * a G-code file's {@code T} commands mean. "This machine has a 0.4 and a 0.6" without saying
     * which one is {@code T0} answers nothing a slicer asked.
     */
    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "printer_nozzle", joinColumns = @JoinColumn(name = "printer_id"))
    @OrderColumn(name = "tool_index")
    @Column(name = "diameter")
    List<Double> nozzleDiameters = new ArrayList<>();

    /** What this machine is set up to print. Unordered, because no material is the first one. */
    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "printer_material", joinColumns = @JoinColumn(name = "printer_id"))
    @Column(name = "material")
    Set<String> materials = new HashSet<>();

    /** Free-form labels, which is also how this farm expresses groups - see {@link MachineSpec}. */
    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
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

    /**
     * Whether the roster believes there is a connection - <em>derived</em>, and not stored.
     *
     * <p>That is the whole point of the column above. A second persisted field saying the same
     * thing is a second thing that can be wrong, and the one this replaced was wrong permanently.
     */
    public boolean isConnected() {
        return connectionState().isLinked();
    }

    /**
     * Records what {@code core} reported about the connection.
     *
     * <p>Not announced back. The firmware and capability reports are, because a replica needs to
     * learn what was kept; this fact came from {@code core} in the first place and handing it back
     * would be a round trip whose only possible effect is a loop.
     */
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
     * Records what a human declared, replacing whatever was there.
     *
     * <p>Replaced and not merged, unlike {@link #learnedCapability}. A firmware sends its
     * capabilities one line at a time with nothing marking the last, so there is no moment at which
     * the set is known; a person submits a whole form, and a material they removed from it is a
     * material they meant to remove.
     *
     * <p>The collections are cleared and refilled rather than reassigned, because Hibernate tracks
     * the instance it handed out - swapping in a new one orphans the old and throws on flush.
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

    /**
     * Records what {@code M115} said, replacing whatever was there.
     *
     * <p>Replaced rather than merged: a firmware report is a complete statement about one build, so
     * a key that has stopped being reported has stopped being true.
     */
    public void learnedFirmware(Map<String, String> reported, LocalDateTime when) {
        firmware.clear();
        firmware.putAll(reported);
        profileLearnedAt = when;
    }

    /**
     * Records one capability, leaving the others alone.
     *
     * <p>Upserted rather than replaced, because the firmware sends these one line at a time and
     * nothing marks the last one - there is no moment at which the set is known to be complete.
     */
    public void learnedCapability(String capability, boolean enabled, LocalDateTime when) {
        capabilities.put(capability, enabled);
        profileLearnedAt = when;
    }
}
