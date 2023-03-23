package org.qw3rtrun.p3d.g.encoder;

import org.qw3rtrun.p3d.g.code.GCode;

public interface Encoder<T extends GCode> {

    String encode(T payload);
}
