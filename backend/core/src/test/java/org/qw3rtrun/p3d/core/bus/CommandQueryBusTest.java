package org.qw3rtrun.p3d.core.bus;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.core.msg.DisconnectedEvent;
import org.qw3rtrun.p3d.core.msg.GEvent;

import java.util.List;

class CommandQueryBusTest {

    @Test
    void send() {
        var gateway = new CommandQueryBus(List.of(
                new CommandQueryService<>(GEvent.class, GEvent::toString)
        ));

        System.out.println(gateway.send(new DisconnectedEvent(), String.class));
    }
}
