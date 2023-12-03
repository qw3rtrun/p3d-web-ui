package org.qw3rtrun.p3d.g.code.decoder;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.g.code.GenericGCode;

import java.util.Optional;

@Slf4j
public class GenericDecoder implements GCodeDecoder<GenericGCode> {

    @Override
    public Optional<GenericGCode> decode(String line) {
        return GenericGCode.parse(line);
    }
}
