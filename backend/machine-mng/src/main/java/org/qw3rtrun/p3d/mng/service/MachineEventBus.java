package org.qw3rtrun.p3d.mng.service;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.core.msg.ManagementEvent;
import org.springframework.context.ApplicationEventPublisher;

@RequiredArgsConstructor
public class MachineEventBus {

    private final ApplicationEventPublisher publisher;

    Void push(ManagementEvent event) {
        publisher.publishEvent(event);
        return null;
    }
}
