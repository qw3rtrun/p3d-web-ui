package org.qw3rtrun.p3d.g.code.core;

import java.util.List;

public interface GCodec {
    String encode(GElement... fields);

    String encode(List<? extends GElement> fields);

    List<GElement> decode(String line);
}
