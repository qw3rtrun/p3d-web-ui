package org.qw3rtrun.p3d.g.code.core;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GCoreCodecTest {

    GCoreCodec codec = new GCoreCodec();
    @Test
    void decodeLine() {
        System.out.println(codec.splitFields("M105").collect(Collectors.toList()));
        System.out.println(codec.splitFields("M105 S1").collect(Collectors.toList()));
        System.out.println(codec.splitFields("M105 S1 F4").collect(Collectors.toList()));
        System.out.println(codec.splitFields("M105 S0.5 B Ptest.gco").collect(Collectors.toList()));
        System.out.println(codec.splitFields("  M105   S0.5    B    Ptest.gco    ").collect(Collectors.toList()));
        System.out.println(codec.splitFields("M105 S0.5 B P\"test gco\"").collect(Collectors.toList()));
        System.out.println(codec.splitFields("M105 S0.5 B P\"test gco").collect(Collectors.toList()));
        System.out.println(codec.splitFields("M105 S0.5 B P\"test gco\"\"").collect(Collectors.toList()));
        System.out.println(codec.splitFields("M105 S0.5 B P\"\"test gco\"\"").collect(Collectors.toList()));
        System.out.println(codec.splitFields("M105 S0.5 B P\"test gco\" G\"foo\"\"boo\" C ").collect(Collectors.toList()));
        System.out.println(codec.splitFields("\"").collect(Collectors.toList()));
        System.out.println(codec.splitFields("M105 \"Fi \"\" le\"").collect(Collectors.toList()));

    }
}
