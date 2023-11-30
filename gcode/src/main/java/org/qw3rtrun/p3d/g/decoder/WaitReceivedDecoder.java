package org.qw3rtrun.p3d.g.decoder;

import org.apache.commons.lang3.StringUtils;
import org.qw3rtrun.p3d.g.event.WaitReceived;

import java.util.Optional;

public class WaitReceivedDecoder implements GEventDecoder<WaitReceived> {

    private static final String WAIT_PREFIX = "wait";

    @Override
    public Optional<WaitReceived> decode(String line) {
        if (line.length() != 4 || !StringUtils.startsWithIgnoreCase(line, WAIT_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(WaitReceived.getInstance());
    }
}
