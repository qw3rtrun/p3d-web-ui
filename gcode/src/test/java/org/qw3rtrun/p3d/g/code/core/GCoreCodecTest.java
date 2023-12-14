package org.qw3rtrun.p3d.g.code.core;

import org.junit.jupiter.api.Test;

class GCoreCodecTest {

    GCoreCodec codec = new GCoreCodec(XorCheckSum::new, new GCoreEncoder());

    @Test
    void decodeLine() {
        System.out.println(codec.decode("M105"));
        System.out.println(codec.decode("M105 S1"));
        System.out.println(codec.decode("M105 S1 F4"));
        System.out.println(codec.decode("M105 S0.5 B Ptest.gco"));
        System.out.println(codec.decode("  M105   S0.5    B    Ptest.gco    "));
        System.out.println(codec.decode("M105 S0.5 B P\"test gco\""));
        System.out.println(codec.decode("M105 S0.5 B P\"a ;* \"\" b\""));
        System.out.println(codec.decode("M105 S0.5 B P\"test gco\""));
        System.out.println(codec.decode("M105 S0.5 B P\"test gco\"\""));
        System.out.println(codec.decode("M105 S0.5 B P\"\"test gco\"\""));
        System.out.println(codec.decode("M105 S0.5 B P\"test gco\" G\"foo\"\"boo\" C "));
        System.out.println(codec.decode("\" \""));
        System.out.println(codec.decode("M105 \"Fi \"\" le\""));

        //System.out.println(codec.decode("M105 S1*71"));
        //System.out.println(codec.decode("M105 S1 *71"));
        //System.out.println(codec.decode("M105 S1 *71*71"));
        System.out.println(codec.decode("M105 S1 ;*71 ;comment *71"));
        System.out.println(codec.decode("M105 S1 \"*71\""));
    }

}
