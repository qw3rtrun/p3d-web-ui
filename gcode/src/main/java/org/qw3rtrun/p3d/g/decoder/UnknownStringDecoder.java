package org.qw3rtrun.p3d.g.decoder;

import org.qw3rtrun.p3d.g.event.ReceivedUnknownEvent;
import org.qw3rtrun.p3d.core.msg.UnknownEvent;

import java.util.Optional;

public class UnknownStringDecoder implements GEventDecoder<UnknownEvent> {

    @Override
    public Optional<UnknownEvent> decode(String line) {
        return Optional.of(new ReceivedUnknownEvent(line));
    }
}
