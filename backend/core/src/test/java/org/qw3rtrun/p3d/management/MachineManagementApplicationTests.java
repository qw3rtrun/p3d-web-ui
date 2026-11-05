package org.qw3rtrun.p3d.management;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.MConnectionChangedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.management.service.MachineManagementImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the roster remembers about a machine's connection, and where it got it. Used to be a
 * context-load test that added a printer, called {@code service.connect} and asserted nothing -
 * a fair description, since the connect endpoint has always gone to the runtime instead and the
 * persisted flag said {@code false} from registration onwards regardless. Plan item B3 turned that
 * around: {@code core} reports each transition, the roster keeps it, and the startup announcement
 * carries it back so a restart can redial what was connected.
 */
@SpringBootTest
class MachineManagementApplicationTests {

    @Autowired
    private MachineManagementService service;

    @Autowired
    private MachineManagementImpl roster;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private Announcements announcements;

    @TestConfiguration
    static class Config {
        @Bean
        Announcements announcements() {
            return new Announcements();
        }
    }

    static class Announcements {
        final List<MRegisteredEvent> heard = new ArrayList<>();

        @EventListener
        void on(MRegisteredEvent event) {
            heard.add(event);
        }
    }

    @Test
    void aFreshlyRegisteredMachineIsOffline() {
        var added = service.add("never dialled", new ConnectionDetails("host", 123));

        assertEquals(MachineConnectionState.OFFLINE, service.fetch(added.uuid()).orElseThrow().state());
    }

    // each of the six, so a state added to the enum without a wide enough column fails here rather
    // than on somebody's farm.
    @Test
    void whateverTheRuntimeReportsIsWrittenDown() {
        var added = service.add("reports", new ConnectionDetails("host", 123));

        for (var state : MachineConnectionState.values()) {
            publisher.publishEvent(new MConnectionChangedEvent(
                    added.uuid(), state, "under test", LocalDateTime.now()));

            assertEquals(state, service.fetch(added.uuid()).orElseThrow().state());
        }
    }

    // re-announcing would hand it straight back to core, and PrinterReactorManager redials on that
    // announcement - the round trip has exactly one possible effect, a loop.
    @Test
    void aConnectionChangeIsNotAnnouncedBack() {
        var added = service.add("quiet", new ConnectionDetails("host", 123));
        announcements.heard.clear();

        publisher.publishEvent(new MConnectionChangedEvent(
                added.uuid(), MachineConnectionState.IDLE, null, LocalDateTime.now()));

        assertTrue(announcements.heard.isEmpty(),
                "the roster announced a fact core had just told it: " + announcements.heard);
    }

    // without this the persisted column would have no reader, and B3 would have moved a write-only
    // flag rather than removed one.
    @Test
    void theStartupAnnouncementCarriesTheConnectionState() {
        var added = service.add("was printing", new ConnectionDetails("host", 123));
        publisher.publishEvent(new MConnectionChangedEvent(
                added.uuid(), MachineConnectionState.BUSY, null, LocalDateTime.now()));
        announcements.heard.clear();

        roster.announceTheFarm();

        var announced = announcements.heard.stream()
                .filter(event -> event.getMachineId().equals(added.uuid()))
                .findFirst().orElseThrow(() -> new AssertionError("the machine was not announced"));
        assertEquals(MachineConnectionState.BUSY, announced.getState());
        assertTrue(announced.getState().isLinked());
    }

    /** {@code CONNECTING} is an attempt in flight, which is not a connection. */
    @Test
    void anAttemptInFlightDoesNotCountAsConnected() {
        assertFalse(MachineConnectionState.CONNECTING.isLinked());
        assertTrue(MachineConnectionState.ERROR.isLinked());
    }
}
