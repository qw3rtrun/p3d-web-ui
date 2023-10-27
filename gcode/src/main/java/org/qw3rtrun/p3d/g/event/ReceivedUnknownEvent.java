package org.qw3rtrun.p3d.g.event;

import org.qw3rtrun.p3d.core.msg.UnknownEvent;

public record ReceivedUnknownEvent(String text) implements UnknownEvent {
}
