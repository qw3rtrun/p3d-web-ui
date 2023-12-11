package org.qw3rtrun.p3d.g.code.decoder;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.code.GCode;
import org.qw3rtrun.p3d.g.code.SetHotendTemperature;

import static org.qw3rtrun.p3d.g.code.core.G.*;

class CommandDecoderDescriptorTest {

    private CommandDecoderDescriptor<SetHotendTemperature> decoder =
            CommandDecoderDescriptor.build(SetHotendTemperature.class,
                    SetHotendTemperature.class.getAnnotation(GCode.class));

    @Test
    void simpleTest() {
        var cmd = decoder.decode(M(104, T(0), S(250)));
        System.out.println(cmd);
    }
}
