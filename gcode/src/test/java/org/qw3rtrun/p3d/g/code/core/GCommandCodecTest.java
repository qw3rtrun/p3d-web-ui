package org.qw3rtrun.p3d.g.code.core;

import org.junit.jupiter.api.Test;

class GCommandCodecTest {

    private GCommandCodec codec = new GCommandCodec(new GCoreCodec());

    @Test
    void test() {
        var gcode = codec.decode("M105 S0.5 B P\"test gco\" G\"foo\"\"boo\" C  \"literal\" ;Comment P2");
        System.out.println(gcode);
    }
}
