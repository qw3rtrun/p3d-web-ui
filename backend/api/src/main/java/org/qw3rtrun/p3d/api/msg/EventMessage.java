package org.qw3rtrun.p3d.api.msg;

import lombok.NonNull;

import java.util.UUID;

public record EventMessage(@NonNull UUID id, @NonNull String type, @NonNull GEvent event) {
    public EventMessage(@NonNull UUID id, @NonNull GEvent event) {
        this(id, event.getClass().getSimpleName(), event);
    }
}
