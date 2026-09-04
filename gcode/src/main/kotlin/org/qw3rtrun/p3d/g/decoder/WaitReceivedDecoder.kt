package org.qw3rtrun.p3d.g.decoder

import org.apache.commons.lang3.StringUtils
import org.qw3rtrun.p3d.g.event.WaitReceived
import java.util.Optional

class WaitReceivedDecoder : GEventDecoder<WaitReceived> {

    override fun decode(line: String): Optional<WaitReceived> {
        if (line.length != 4 || !line.startsWith(WAIT_PREFIX, ignoreCase = true)) {
            return Optional.empty()
        }
        return Optional.of(WaitReceived.getInstance())
    }

    companion object {
        private const val WAIT_PREFIX = "wait"
    }
}
