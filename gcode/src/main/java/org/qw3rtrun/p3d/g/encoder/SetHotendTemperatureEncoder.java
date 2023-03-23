package org.qw3rtrun.p3d.g.encoder;

import org.qw3rtrun.p3d.g.code.SetHotendTemperature;

import static java.lang.String.format;

public class SetHotendTemperatureEncoder implements Encoder<SetHotendTemperature> {
    @Override
    public String encode(SetHotendTemperature payload) {
        return format("M104 T%d S%.2f", payload.index(), payload.temp());
    }
}
