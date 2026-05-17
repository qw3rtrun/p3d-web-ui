package org.qw3rtrun.p3d.mng.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.qw3rtrun.p3d.core.msg.MConnectedEvent;
import org.qw3rtrun.p3d.core.msg.MDisconnectedEvent;

import java.time.LocalDateTime;
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

    public MConnectedEvent connect() {
        connected = true;
        return new MConnectedEvent(id, LocalDateTime.now());
    }

    public MDisconnectedEvent disconnect() {
        connected = false;
        return new MDisconnectedEvent(id, LocalDateTime.now());
    }
}
