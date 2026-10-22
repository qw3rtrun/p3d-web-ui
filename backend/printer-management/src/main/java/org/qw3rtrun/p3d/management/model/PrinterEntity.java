package org.qw3rtrun.p3d.management.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.qw3rtrun.p3d.api.msg.MConnectedEvent;
import org.qw3rtrun.p3d.api.msg.MDisconnectedEvent;

import org.qw3rtrun.p3d.api.model.MachineProfile;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
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
    boolean connected;

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

    /** A machine as it is registered: reachable, and nothing known about it yet. */
    public PrinterEntity(UUID id, String name, String host, int port) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.connected = false;
    }

    public MConnectedEvent connect() {
        connected = true;
        return new MConnectedEvent(id, LocalDateTime.now());
    }

    public MDisconnectedEvent disconnect() {
        connected = false;
        return new MDisconnectedEvent(id, LocalDateTime.now());
    }

    /** What this machine turned out to be, or {@link MachineProfile#unknown()} if nothing ever asked. */
    public MachineProfile profile() {
        return profileLearnedAt == null
                ? MachineProfile.unknown()
                : new MachineProfile(Map.copyOf(firmware), Map.copyOf(capabilities), profileLearnedAt);
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
