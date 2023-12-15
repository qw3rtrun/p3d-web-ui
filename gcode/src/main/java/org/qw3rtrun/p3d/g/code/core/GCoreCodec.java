package org.qw3rtrun.p3d.g.code.core;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.function.Supplier;

@RequiredArgsConstructor
public class GCoreCodec implements GCodec {

    private final Supplier<? extends CheckSum> checksum;

    private final GCoreEncoder encoder;

    @Override
    public String encode(@NonNull List<? extends GElement> fields) {
        if (fields.isEmpty()) {
            return "";
        }

        if (fields.get(0) instanceof GIntField fi && fi.isN()) {
            if (fields.get(fields.size() - 1) instanceof GComment gc) {
                return encoder.encode(fields.subList(0, fields.size() - 1), checksum.get(), gc);
            } else {
                return encoder.encode(fields, checksum.get());
            }
        } else {
            return encoder.encode(fields);
        }
    }

    @Override
    public List<GElement> decode(@NonNull String line) {
        return new GCoreDecoder(line, checksum.get()).parse();
    }
}
