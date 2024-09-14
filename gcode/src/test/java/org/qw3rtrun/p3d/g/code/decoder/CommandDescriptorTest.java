package org.qw3rtrun.p3d.g.code.decoder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.code.SetHotendTemperature;
import org.qw3rtrun.p3d.g.code.descr.CommandDescriptor;
import org.qw3rtrun.p3d.g.code.descr.DescriptorCreator;
import org.qw3rtrun.p3d.g.code.descr.GCode;

import static org.qw3rtrun.p3d.g.code.cmd.G.*;

class CommandDescriptorTest {

    private final DescriptorCreator creator = new DescriptorCreator();

    private CommandDescriptor<SetHotendTemperature> decoder;

    @BeforeEach
    void setUp() {
        decoder =
                creator.build(SetHotendTemperature.class,
                        SetHotendTemperature.class.getAnnotation(GCode.class));
    }

    @Test
    void simpleTest() {
        var cmd = decoder.decode(M(104, T(0), S(250)));
        System.out.println(cmd);
    }
}
