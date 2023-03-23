package org.qw3rtrun.p3d.g.event;

public interface AdvancedOkReceivedEvent extends OKReceivedEvent {

    int planner();

    int blockQueue();

    int lineNumber();
}
