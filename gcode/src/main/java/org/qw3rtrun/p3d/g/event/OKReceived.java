package org.qw3rtrun.p3d.g.event;

public record OKReceived() implements OKReceivedEvent {

    @Override
    public String toString() {
        return "ok";
    }
}
