package org.qw3rtrun.p3d.g.event;

import lombok.Getter;
import org.qw3rtrun.p3d.core.msg.WaitReceivedEvent;

public record WaitReceived() implements WaitReceivedEvent {

    private static final WaitReceived instance = new WaitReceived();

    public static WaitReceived getInstance() {
        return instance;
    }
}
