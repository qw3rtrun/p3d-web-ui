package org.qw3rtrun.p3d.core.model;

import java.util.UUID;

public record Printer(
        UUID uuid,
        String name,
        PrinterConnection connection
) {
}
