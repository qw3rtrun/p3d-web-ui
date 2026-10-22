package org.qw3rtrun.p3d.api.msg;

public interface AdvancedOkReceivedEvent extends OKReceivedEvent {

    int planner();

    int blockQueue();

    int lineNumber();
}
