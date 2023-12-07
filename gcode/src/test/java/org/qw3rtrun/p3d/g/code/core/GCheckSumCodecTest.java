package org.qw3rtrun.p3d.g.code.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GCheckSumCodecTest {

    private final GCheckSumCodec codec = new GCheckSumCodec(new GCoreCodec());

    @Test
    void encode() {
        System.out.println(codec.encode(0, G.M(155, G.S(1))));
    }
}
