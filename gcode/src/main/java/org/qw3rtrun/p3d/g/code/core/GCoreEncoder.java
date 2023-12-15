package org.qw3rtrun.p3d.g.code.core;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GCoreEncoder {

    public String encode(@NonNull List<? extends GElement> fields) {
        assertEmpty(fields);
        return fields.stream().map(GElement::asString).collect(Collectors.joining(" "));
    }

    public String encode(@NonNull List<? extends GElement> fields, @NonNull CheckSum checksum) {
        var encode = encode(fields);
        checksum.add(encode);
        return STR."\{encode}*\{checksum.getString()}";
    }

    public String encode(@NonNull List<? extends GElement> fields, @NonNull CheckSum checksum, @NonNull GComment comment) {
        return STR."\{encode(fields, checksum)} \{encode(List.of(comment))}";

    }

    private void assertEmpty(List<? extends GElement> fields) {
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("GCode fields should be not empty");
        }
    }

}
