package org.qw3rtrun.p3d.g.code.core;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.function.Supplier;

@RequiredArgsConstructor
public class GCoreCodec implements GCodec {

    private final Supplier<? extends CheckSum> checksum;

    private final GCoreEncoder encoder;

    @Override
    public String encode(List<? extends GElement> fields) {
        return encoder.encode(fields);
    }

    @Override
    public List<GElement> decode(String line) {
        return new GCoreDecoder(line, checksum.get()).parse();
    }
}
