package org.qw3rtrun.p3d.g.event;

public record AdvancedOKReceived(int planner, int blockQueue, int lineNumber) implements AdvancedOkReceivedEvent {
}
