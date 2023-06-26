package org.qw3rtrun.p3d.api.dto;

import lombok.NonNull;
import org.qw3rtrun.p3d.g.event.GEvent;

import java.util.UUID;

public record EventMessage(@NonNull UUID id, @NonNull String type, @NonNull GEvent event) {
    public EventMessage(@NonNull UUID id, @NonNull GEvent event) {
        this(id, event.getClass().getSimpleName(), event);
    }
}
