package org.qw3rtrun.p3d.g.code.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.code.cmd.GCommandCodec;

import static org.qw3rtrun.p3d.g.code.cmd.G.*;

class GCommandCodecTest {

    private GCommandCodec codec = new GCommandCodec(new GCoreCodec(XorCheckSum::new, new GCoreEncoder()));

    @Test
    void test() {
        var gcode = codec.decode("M105 S0.5 B P\"test gco\" G\"foo\"\"boo\" C  \"literal\" ;Comment P2");
        System.out.println(gcode);
    }

    @Test
    void encode() {
        var gcode = M(105, T(1), S(150));
        System.out.println(gcode);
    }

}
