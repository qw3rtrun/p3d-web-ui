package org.qw3rtrun.p3d.g.code.decoder;

import org.qw3rtrun.p3d.g.code.GEncodable;

import java.util.Optional;

public interface GCodeDecoder<T extends GEncodable> {

    Optional<T> decode(String line);
}
