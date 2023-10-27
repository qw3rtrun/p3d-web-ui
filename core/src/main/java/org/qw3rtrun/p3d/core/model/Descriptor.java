package org.qw3rtrun.p3d.core.model;

import java.util.UUID;

public record Descriptor(
        UUID uuid,
        String name,
        ConnectionDetails connection,
        boolean connected
) {
}
