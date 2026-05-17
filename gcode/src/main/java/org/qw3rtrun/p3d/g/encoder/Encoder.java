package org.qw3rtrun.p3d.g.encoder;

import org.qw3rtrun.p3d.g.code.descr.GEncodable;

public interface Encoder<T extends GEncodable> {

    String encode(T payload);
}
