package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The whole farm on one stream - issue #43's feature 8, through {@code GET /api-farm/stream}.
 *
 * <p><strong>It is the one test here with more than one machine in it</strong>, and that is not
 * decoration. Every other class drives a feature of <em>a</em> printer and could not tell a farm
 * that routes every event correctly from one that puts them all under the first id it thought of.
 * This endpoint exists because a dashboard holds one {@code EventSource} rather than one per
 * printer, so "two machines, one subscription, and each one's events arrive under its own
 * {@code EventMessage.id}" is the feature, not a detail of it.
 *
 * <p>Three claims, and each has a failure mode that has actually shipped in something like it:
 *
 * <ul>
 *   <li><strong>it opens nothing.</strong> The obvious wiring for a farm view is to watch every
 *       machine through {@code PrinterControl.updates(id)}, which instantiates a runtime as a side
 *       effect - forty reactors, and on the version of that code that dialled on lookup, forty
 *       sockets, to render a page that is only looking. {@code FarmEvents} listens on the
 *       application event bus instead. What is observable from out here is the dial, and
 *       {@link MockPrinter#sessions()} is where it would show;</li>
 *   <li><strong>a late subscriber is told where everything is.</strong> There is no replay sink
 *       behind this stream - one would hold the farm's last event rather than each machine's, which
 *       is whichever printer spoke most recently and is useless - so a snapshot is built per
 *       subscription. Without it a dashboard opened on a quiet farm shows a row of blanks until
 *       each machine next says something, which for an idle printer is never;</li>
 *   <li><strong>membership changes reach the page.</strong> A printer registered or deleted while
 *       the page is open appears or disappears without a reload, as {@code MachineJoinedFarm} and
 *       {@code MachineLeftFarm}.</li>
 * </ul>
 *
 * <h2>What the configurations are here</h2>
 *
 * §5 of the integration-test spec asks a feature to be driven over every configuration where the
 * answer should differ, and for this one <em>no capability changes any answer</em>: a connection
 * state is announced the same way by a machine with a card and one without. The axis that does
 * change the answer is the one no other class varies - <em>how many machines</em> - so the matrix
 * here is one machine against two, and the pairs below are deliberately two different profiles so
 * that the demultiplexing is proved across machines that are genuinely not the same.
 *
 * <h2>The mid-snapshot window, and why nothing below depends on it</h2>
 *
 * {@code FarmEvents.updates()} is the snapshot {@code concatWith} the live sink, so the sink is
 * subscribed only once the snapshot has finished - and anything emitted in between reaches nobody.
 * The window is milliseconds and it is open; closing it means subscribing the sink before
 * prepending the snapshot, which is its own change and not this one. So every live event asserted
 * below is caused by <strong>a request this test makes after the snapshot has arrived</strong> -
 * an HTTP round trip later, which is orders of magnitude outside the window. Nothing here asserts
 * that the window is closed, and nothing here would pass only because it is.
 */
class FarmDashboardIntegrationTest extends FarmIntegrationTest {

    /**
     * Two machines, one subscription, and each one's events arrive under its own id.
     *
     * <p>The connection walk proves both are carried; the reading proves they are kept apart.
     * Asking one machine for a temperature and finding it under the other machine's id is the
     * failure a single stream can have and forty streams cannot, and it is invisible to every other
     * test in this module - a per-printer stream is addressed by the id in its URL, so it cannot
     * mislabel anything.
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
     * Holding the stream open dials nothing, which is the reason the endpoint exists.
     *
     * <p>The claim is a negative, so it is given time to be wrong - and the positive case it is
     * measured against is next door: {@code ConnectivityIntegrationTest} gets a machine from
     * registered to {@code IDLE} in a round trip on loopback.
     *
     * <p><strong>What is observable from here is the dial and not the reactor.</strong> A farm view
     * wired through {@code PrinterControl.updates(id)} would build a runtime per machine, and a
     * runtime is a scheduler, a sink and a command queue that nothing asked for; whether one exists
     * is not visible through any endpoint, and §3 forbids reaching in to look. The socket is, and
     * it is the part a printer on the other side of the room would notice.
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
     * A page opened long after the farm started is told where every machine is, before anything else.
     *
     * <p>One connected, one never dialled, and the subscription comes last - so everything the
     * stream would have carried live has already happened and been missed. What arrives is built
     * rather than replayed, and the shape says which: a snapshot entry states where a machine
     * <em>is</em>, so {@code from} and {@code to} are the same state, while a real transition states
     * where it <em>went</em>.
     *
     * <p>The second half is the ordering, and it is structural rather than lucky:
     * {@code concatWith} does not subscribe the live sink until the snapshot completes, so no live
     * event can get in front of it. The reading asked for below therefore has to land behind the
     * snapshot entry, and the first thing this machine ever said to this subscriber stays the
     * snapshot.
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
     * A machine plugged in while the page is open joins the farm, by name.
     *
     * <p>{@code MachineJoinedFarm} is the only thing on this stream that carries what a printer is
     * called, because it is the only one a dashboard has to draw a new row from; a machine that was
     * already there arrives in the snapshot instead, and the page already has its name from the
     * roster. So the two paths onto a page are different and the assertion below says so: the
     * machine registered before the subscription must <em>not</em> announce itself as an arrival.
     *
     * <p>{@code MRegisteredEvent} is a statement of current fact, re-published for every machine at
     * startup and again whenever the roster learns something - a firmware report, an edited spec.
     * Taking each one as an arrival would tell a dashboard that a printer had just appeared every
     * time it said what it was, which is what {@code FarmEvents}'s set of known machines prevents.
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
     * A machine deleted while the page is open leaves it, and the others are untouched.
     *
     * <p>The second half is what makes this a farm test rather than a second copy of
     * {@code PrinterManagementIntegrationTest}'s delete: on one stream shared by every machine, a
     * removal that reached the wrong id - or every id - would read as the whole farm vanishing from
     * a page while the printers carried on printing.
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
     * Waits until the snapshot has carried each of these machines.
     *
     * <p>Two things at once, and the second is the load-bearing one. It is the assertion that a
     * dashboard knows where every machine is the moment it attaches - and it is also the only
     * signal a test gets that the <em>live</em> sink is subscribed, because the snapshot is what
     * precedes it. Causing anything before this has arrived would be racing the subscription.
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
     * The dial, as this machine walked it on the shared stream - the tail of its transitions.
     *
     * <p>Only the tail, because the first entry is the snapshot: wherever the machine happened to
     * be when this subscriber attached, which is not part of the claim. What is part of the claim is
     * that merging two machines onto one stream does not reorder either of them - a page told
     * {@code ONLINE} after {@code IDLE} draws a machine that just became unusable.
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
