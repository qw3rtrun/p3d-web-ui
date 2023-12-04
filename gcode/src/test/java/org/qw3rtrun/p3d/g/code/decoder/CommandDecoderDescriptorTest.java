package org.qw3rtrun.p3d.g.code.decoder;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.code.AutoReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.GCommand;
import org.qw3rtrun.p3d.g.code.SetHotendTemperature;

class CommandDecoderDescriptorTest {

    private CommandDecoderDescriptor<SetHotendTemperature> decoder =
            CommandDecoderDescriptor.build(SetHotendTemperature.class,
                    SetHotendTemperature.class.getAnnotation(GCommand.class));

    private GenericDecoder generic = new GenericDecoder();

    @Test
    void simpleTest() {
        var gcode = "M104 T1 S150";
        var cmd = decoder.decode(generic.decode(gcode).get());
        System.out.println(cmd);
    }
}
