package org.qw3rtrun.p3d.g.code.decoder;

import org.qw3rtrun.p3d.g.code.GCode;

import java.util.Optional;

public interface GCodeDecoder<T extends GCode> {

    Optional<T> decode(String line);
}
