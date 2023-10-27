package org.qw3rtrun.p3d.printersmng.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

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

    public void connect() {
        connected = true;
    }

    public void disconnect() {
        connected = false;
    }
}
