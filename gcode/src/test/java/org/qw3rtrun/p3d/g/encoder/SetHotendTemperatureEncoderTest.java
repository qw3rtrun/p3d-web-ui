package org.qw3rtrun.p3d.g.encoder;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.code.SetHotendTemperature;

import static org.junit.jupiter.api.Assertions.*;

class SetHotendTemperatureEncoderTest {

    private final SetHotendTemperatureEncoder encoder = new SetHotendTemperatureEncoder();

    @Test
    void encode() {
        assertEquals("M104 T0 S10.00", encoder.encode(SetHotendTemperature.m104(10)));
    }
}
