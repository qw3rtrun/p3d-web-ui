package org.qw3rtrun.p3d.g.code.core;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GCoreEncoder {

    private final CheckSum checksum;

    public String encode(@NonNull List<? extends GElement> fields) {
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("GCode fields in empty");
        }
        if (fields.get(0) instanceof GIntField i && i.letter() == 'N') {

        }
        return fields.stream().map(GElement::asString).collect(Collectors.joining(" "));
    }

}
