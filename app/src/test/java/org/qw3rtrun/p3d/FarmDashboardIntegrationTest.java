package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The whole farm on one stream - issue #43 feature 8, {@code GET /api-farm/stream}. The only class
 * here with more than one machine, because only two machines can prove the stream keeps them apart
 * rather than mislabelling them under the first id it thought of.
 *
 * <p>Three claims, each with a failure mode that has shipped in something like it: it opens
 * nothing - a farm view wired through {@code PrinterControl.updates(id)} would dial a runtime,
 * forty reactors and forty sockets, per machine just to render a page that is only looking;
 * {@link MockPrinter#sessions()} is where that dial would show. A late subscriber is told where
 * everything is - there is no replay sink behind this stream (one would hold whichever machine
 * spoke last, which is useless), so a snapshot is built per subscription. Membership changes reach
 * the page, as {@code MachineJoinedFarm} and {@code MachineLeftFarm}.
 *
 * <p>§5 of the integration-test spec drives a feature over every configuration where the answer
 * differs; here that axis is how many machines, not capability, so the matrix is one machine
 * against two, on deliberately different profiles.
 *
 * <p>{@code FarmEvents.updates()} is the snapshot {@code concatWith} the live sink, so anything
 * emitted in the millisecond window before the sink subscribes reaches nobody; closing that window
 * is a separate change. Every live event below is caused by a request made well after the snapshot
 * arrives, so nothing here depends on the window being closed.
 */
class FarmDashboardIntegrationTest extends FarmIntegrationTest {

    /**
     * The connection walk proves both machines are carried; the reading proves they are kept apart.
     * Finding alpha's reading under beta's id is a failure only a shared stream can have, and is
     * invisible to every other test here - a per-printer stream is addressed by the id in its URL,
     * so it cannot mislabel anything.
     */
    @Test
    void oneStreamCarriesEveryMachineAndKeepsThemApart() {
        var first = machine(MockPrinter.marlin());
        var second = machine(MockPrinter.legacyOk());
        var alpha = register("alpha", first);
        var beta = register("beta", second);
        var dashboard = watchTheFarm();
        awaitSnapshot(dashboard, alpha, beta);

        connect(alpha);
        connect(beta);

        awaitIdleOn(dashboard, alpha);
        awaitIdleOn(dashboard, beta);
        assertEquals(List.of("CONNECTING", "ONLINE", "IDLE"), walkOn(dashboard, alpha),
                "each machine's own walk survives being merged with another's: "
                        + dashboard.byMachine());
        assertEquals(List.of("CONNECTING", "ONLINE", "IDLE"), walkOn(dashboard, beta));

        command(alpha, "/report-temp").expectStatus().isOk();

        var reading = dashboard.await(alpha, "OkTemperatureRs");
        assertEquals(210.0, degrees(reading), "the browser receives the figure alpha stated");
        assertFalse(dashboard.saw(beta, "OkTemperatureRs"),
                "only alpha was asked, so a reading under beta is one stream mislabelling its "
                        + "machines: " + dashboard.byMachine());
    }

    /**
     * A negative claim, so it is given time to be wrong; the positive case next door is
     * {@code ConnectivityIntegrationTest}, getting a machine from registered to {@code IDLE} in a
     * round trip on loopback. What is observable from here is the dial, not the reactor - §3 forbids
     * reaching in to check whether one exists, but the socket is what a printer across the room
     * would notice.
     */
    @Test
    void holdingTheStreamDialsNothing() {
        var first = machine(MockPrinter.marlin());
        var second = machine(MockPrinter.withoutCard());
        var alpha = register("looked at", first);
        var beta = register("also looked at", second);

        var dashboard = watchTheFarm();

        awaitSnapshot(dashboard, alpha, beta);
        quiet();
        assertEquals(0, first.sessions(), "subscribing to the farm must not dial a machine");
        assertEquals(0, second.sessions(), "and not the second one either");
        assertFalse(first.sessionOpen());
        assertFalse(second.sessionOpen());

        assertEquals("OFFLINE", stateOn(dashboard, alpha),
                "a machine with no runtime is offline, and that is the truth rather than a default");
        assertEquals("OFFLINE", stateOn(dashboard, beta));
    }

    /**
     * One connected, one never dialled, subscribed last, so nothing the stream would have carried
     * live has reached this subscriber - what arrives is built, not replayed: a snapshot entry's
     * {@code from} equals its {@code to} (a state, not a transition), unlike a real one.
     */
    @Test
    void aLateSubscriberIsToldWhereEveryMachineIsBeforeAnythingHappens() {
        var running = machine(MockPrinter.marlin());
        var dark = machine(MockPrinter.marlin());
        var live = register("on the line", running);
        var unplugged = register("never dialled", dark);
        connectAndWait(live);

        var dashboard = watchTheFarm();

        awaitSnapshot(dashboard, live, unplugged);
        var opening = dashboard.firstFrom(live);
        assertEquals("ConnectionStateChanged", opening.get("type"),
                "the first thing a dashboard hears about a machine is where it is: "
                        + dashboard.byMachine());
        var where = payload(opening);
        assertEquals("IDLE", where.get("to"), "this one is connected, and the snapshot says so");
        assertEquals(where.get("from"), where.get("to"),
                "a snapshot states a position; only a transition has a from that differs");
        assertEquals("OFFLINE", payload(dashboard.firstFrom(unplugged)).get("to"),
                "and nothing has dialled the other one, so there is nothing it could be doing");

        command(live, "/report-temp").expectStatus().isOk();

        dashboard.await(live, "OkTemperatureRs");
        assertEquals("ConnectionStateChanged", dashboard.typesFrom(live).getFirst(),
                "the snapshot comes first and stays first: " + dashboard.byMachine());
    }

    /**
     * {@code MachineJoinedFarm} is the only message on this stream carrying a printer's name,
     * because it is the only one a dashboard draws a new row from; a machine already present arrives
     * via the snapshot instead, with its name from the roster.
     *
     * <p>{@code MRegisteredEvent} is republished whenever the roster learns anything - a firmware
     * report, an edited spec - not only on first appearance; {@code FarmEvents}'s set of known
     * machines is what stops every republish reading as a new arrival.
     */
    @Test
    void aMachineRegisteredWhileThePageIsOpenJoinsTheFarm() {
        var standing = machine(MockPrinter.marlin());
        var existing = register("already here", standing);
        var dashboard = watchTheFarm();
        awaitSnapshot(dashboard, existing);

        var arriving = machine(MockPrinter.dualExtruder());
        var joined = register("plugged in just now", arriving);

        var announcement = dashboard.await(joined, "MachineJoinedFarm");
        assertEquals("plugged in just now", announcement.get("name"),
                "a dashboard draws a row from this, so the name travels with it");
        assertFalse(dashboard.saw(existing, "MachineJoinedFarm"),
                "the machine that was already here was in the snapshot, not an arrival: "
                        + dashboard.byMachine());
        assertEquals(0, arriving.sessions(), "and registering it still dials nothing");
    }

    /**
     * What makes this a farm test and not a copy of {@code PrinterManagementIntegrationTest}'s
     * delete: on one stream shared by every machine, a removal reaching the wrong id - or every id -
     * reads as the whole farm vanishing while the other printers carry on.
     */
    @Test
    void aMachineDeletedWhileThePageIsOpenLeavesTheFarm() {
        var staying = machine(MockPrinter.marlin());
        var going = machine(MockPrinter.marlin());
        var kept = register("kept", staying);
        var removed = register("removed", going);
        var dashboard = watchTheFarm();
        awaitSnapshot(dashboard, kept, removed);

        deregister(removed);

        assertNotNull(dashboard.await(removed, "MachineLeftFarm"));
        assertFalse(dashboard.saw(kept, "MachineLeftFarm"),
                "one machine left; the rest of the farm is still there: " + dashboard.byMachine());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Doubles as the only signal a test gets that the live sink is subscribed, since the snapshot
     * precedes it; causing anything before this has arrived would race the subscription.
     */
    private static void awaitSnapshot(Events dashboard, UUID... machines) {
        for (var machine : machines) {
            await(() -> "the snapshot entry for machine " + machine
                            + ". The farm stream carried " + dashboard.byMachine(),
                    () -> dashboard.saw(machine, "ConnectionStateChanged"));
        }
    }

    /** Waits for one machine to be announced as usable, on the stream the whole farm shares. */
    private static void awaitIdleOn(Events dashboard, UUID machine) {
        await(() -> "machine " + machine + " to reach IDLE on the farm stream."
                        + " It carried " + dashboard.byMachine(),
                () -> "IDLE".equals(stateOn(dashboard, machine)));
    }

    /**
     * Only the tail: the first entry is the snapshot (wherever the machine was when this subscriber
     * attached), not part of the claim, which is that merging two machines onto one stream does not
     * reorder either - {@code ONLINE} after {@code IDLE} would draw a machine that just became
     * unusable.
     */
    private static List<String> walkOn(Events dashboard, UUID machine) {
        var walk = dashboard.from(machine).stream()
                .filter(m -> "ConnectionStateChanged".equals(m.get("type")))
                .map(m -> (String) ((Map<?, ?>) m.get("event")).get("to"))
                .toList();
        return walk.size() <= 3 ? walk : walk.subList(walk.size() - 3, walk.size());
    }

    /** Where the stream last said this machine was, or null if it has not said. */
    private static String stateOn(Events dashboard, UUID machine) {
        var latest = dashboard.payloadOf(machine, "ConnectionStateChanged");
        return latest == null ? null : (String) latest.get("to");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> message) {
        assertNotNull(message, "the stream carried nothing for this machine");
        return (Map<String, Object>) message.get("event");
    }

    /** The hotend's current reading out of a temperature event, as a browser reads it. */
    @SuppressWarnings("unchecked")
    private static double degrees(Map<String, Object> reading) {
        var hotend = (Map<String, Object>) reading.get("hotend");
        return ((Number) hotend.get("current")).doubleValue();
    }
}
