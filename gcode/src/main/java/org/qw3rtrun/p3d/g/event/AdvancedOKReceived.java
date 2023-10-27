package org.qw3rtrun.p3d.g.event;

import org.qw3rtrun.p3d.core.msg.AdvancedOkReceivedEvent;

public record AdvancedOKReceived(int planner, int blockQueue, int lineNumber) implements AdvancedOkReceivedEvent {
}
