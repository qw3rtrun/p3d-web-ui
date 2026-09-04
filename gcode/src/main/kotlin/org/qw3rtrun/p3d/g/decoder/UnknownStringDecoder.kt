package org.qw3rtrun.p3d.g.decoder

import org.qw3rtrun.p3d.core.msg.UnknownEvent
import org.qw3rtrun.p3d.g.event.ReceivedUnknownEvent
import java.util.Optional

class UnknownStringDecoder : GEventDecoder<UnknownEvent> {

    override fun decode(line: String): Optional<UnknownEvent> {
        return Optional.of(ReceivedUnknownEvent(line))
    }
}
