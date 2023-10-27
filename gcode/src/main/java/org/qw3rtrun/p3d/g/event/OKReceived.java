package org.qw3rtrun.p3d.g.event;

import org.qw3rtrun.p3d.core.msg.OKReceivedEvent;

public record OKReceived() implements OKReceivedEvent {

    @Override
    public String toString() {
        return "ok";
    }
}
