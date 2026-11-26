package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.protocol.GRq;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which machines a pass of {@link SdStatusPoll} asks, and what it asks them. The three gates and
 * the one byte sequence, with no socket anywhere: {@code SdStatusPoll} decides entirely from
 * {@link PrinterDirectory#all()}, {@link PrinterReactorManager#runtimeOf}, {@code linked()} and
 * {@code profile().hasSdCard()}, all four of which a stand-in runtime can answer.
 *
 * <p>What this cannot see is the thing most worth seeing - that an {@code M27} actually leaves the
 * host every three seconds - because a stand-in for {@code handle(List)} is a stand-in for the hop
 * under test. That is an {@code :app} integration test against {@code MockPrinter}. What it
 * <em>can</em> see is the mistake this layer has made three times: the command never being asked for
 * at all. {@link Runtime#handle} records on <strong>subscribe</strong> rather than on call, so a
 * poll that composes the {@code Mono} and drops it unsubscribed - which is what the obvious one-line
 * body does, since {@code PrinterReactor.handle} is cold - fails here rather than in a quiet farm.
 */
class SdStatusPollTest {

    private final PrinterDirectory directory = new PrinterDirectory();

    private final Map<UUID, Runtime> running = new HashMap<>();

    private final PrinterReactorManager reactors =
            new PrinterReactorManager(directory, PrinterState::new, event -> {
            }) {
                @Override
                public synchronized PrinterReactor runtimeOf(UUID uuid) {
                    return running.get(uuid);
                }
            };

    /** The machines the uploader says are writing to their card right now. */
    private final Set<UUID> writing = new HashSet<>();

    private final SdUploader uploads = new SdUploader(reactors, null) {
        @Override
        public boolean writing(UUID machineId) {
            return writing.contains(machineId);
        }
    };

    private final SdStatusPoll poll = new SdStatusPoll(directory, reactors, uploads);

    /**
     * A runtime with no socket behind it, so the two volatile reads the poll gates on can be set
     * independently of each other and of any connection. Its schedulers are the JVM-cached ones,
     * whose {@code dispose()} is a no-op, so nothing here needs releasing.
     */
    private static final class Runtime extends PrinterReactor {

        private final boolean linked;
        private final MachineProfile profile;
        private final List<GRq<?>> asked = new ArrayList<>();

        Runtime(UUID id, boolean linked, MachineProfile profile) {
            super(null, new PrinterState(id));
            this.linked = linked;
            this.profile = profile;
        }

        @Override
        public boolean linked() {
            return linked;
        }

        @Override
        public MachineProfile profile() {
            return profile;
        }

        @Override
        public Mono<Void> handle(List<GRq<?>> commands) {
            return Mono.fromRunnable(() -> asked.addAll(commands));
        }
    }

    private static MachineProfile withCard() {
        return new MachineProfile(Map.of(), Map.of("SDCARD", true), LocalDateTime.now());
    }

    private static MachineProfile withoutCard() {
        return new MachineProfile(Map.of(), Map.of("SDCARD", false), LocalDateTime.now());
    }

    /** Registers a machine, and gives it a runtime in whatever state the test needs - or none at all. */
    private UUID machine(Runtime runtime) {
        var id = UUID.randomUUID();
        directory.onRegistered(new MRegisteredEvent(id, "a printer", "localhost", 8099,
                MachineProfile.unknown(), MachineSpec.undeclared(),
                MachineConnectionState.OFFLINE, LocalDateTime.now()));
        if (runtime != null) {
            running.put(id, runtime);
        }
        return id;
    }

    private Runtime linkedWithACard() {
        return new Runtime(UUID.randomUUID(), true, withCard());
    }

    /**
     * The whole point: a bare {@code M27}, every pass, on a connected machine that reported
     * {@code Cap:SDCARD}. Asserted as the encoded byte sequence rather than as the command class,
     * because the one thing that must not creep in is the {@code S} word - {@code M27 S3} is the
     * auto-report subscription this farm's firmware accepts and then ignores, which is the measured
     * fact the class exists for.
     */
    @Test
    void aConnectedCardIsAskedForABareM27() {
        var runtime = linkedWithACard();
        machine(runtime);

        poll.askEveryCard();

        assertEquals(List.of("M27"), runtime.asked.stream()
                .map(command -> GEncoder.INSTANCE.encode(command.encode()))
                .toList());
    }

    /** And again next pass, because nothing about a one-shot read is a subscription. */
    @Test
    void itAsksAgainOnEveryPass() {
        var runtime = linkedWithACard();
        machine(runtime);

        poll.askEveryCard();
        poll.askEveryCard();
        poll.askEveryCard();

        assertEquals(3, runtime.asked.size());
    }

    /**
     * A farm nobody has dialled: every pass is a no-op rather than a {@code NullPointerException}
     * out of a scheduled method, which would stop the timer's next pass for every other machine too.
     * {@code runtimeOf} answers null by design and must never be a reason to build one - a dial per
     * registered printer every three seconds is a socket per printer.
     */
    @Test
    void aFarmWithNothingRunningIsAQuietPass() {
        machine(null);
        machine(null);

        poll.askEveryCard();

        assertTrue(running.isEmpty(), "the poll stood a runtime up to ask it something");
    }

    /** A runtime whose link is down: the aggregate would refuse the command anyway, and a refusal per machine every three seconds is a log nobody can read. */
    @Test
    void aDisconnectedMachineIsSkipped() {
        var runtime = new Runtime(UUID.randomUUID(), false, withCard());
        machine(runtime);

        poll.askEveryCard();

        assertEquals(List.of(), runtime.asked);
    }

    /** {@code Cap:SDCARD:0} - the firmware will not answer {@code M27} however often it is asked, and this is the same gate the Storage tab's visibility uses. */
    @Test
    void aMachineWithNoCardReaderIsSkipped() {
        var runtime = new Runtime(UUID.randomUUID(), true, withoutCard());
        machine(runtime);

        poll.askEveryCard();

        assertEquals(List.of(), runtime.asked);
    }

    /**
     * A machine writing to its card is not asked, and not merely asked later: the upload holds the
     * wire exclusively, so an {@code M27} queued now waits behind it and every pass adds one more.
     * Measured 2026-11-26 against the simulator, an 8.5-minute binary transfer released 178 queued
     * {@code M27}s in the three seconds after its {@code M21}. Asked again once the upload is over.
     */
    @Test
    void aMachineWritingToItsCardIsNotPolledUntilTheUploadIsOver() {
        var runtime = linkedWithACard();
        var id = machine(runtime);
        writing.add(id);

        poll.askEveryCard();
        poll.askEveryCard();

        assertEquals(List.of(), runtime.asked, "polled while the upload held the wire");

        writing.remove(id);
        poll.askEveryCard();

        assertEquals(1, runtime.asked.size(), "not polled again once the upload ended");
    }

    /**
     * One pass over a farm, not one machine: a gate that returned instead of continuing would ask
     * the first eligible machine and silently stop at the first skipped one. Ordered so the skips
     * come first, which is the arrangement that fails under that mistake.
     */
    @Test
    void onePassCoversEveryEligibleMachineInTheFarm() {
        var offline = new Runtime(UUID.randomUUID(), false, withCard());
        var cardless = new Runtime(UUID.randomUUID(), true, withoutCard());
        var first = linkedWithACard();
        var second = linkedWithACard();
        machine(offline);
        machine(cardless);
        machine(null);
        machine(first);
        machine(second);

        poll.askEveryCard();

        assertEquals(1, first.asked.size(), "the first eligible machine");
        assertEquals(1, second.asked.size(), "the second eligible machine, behind three skips");
        assertEquals(List.of(), offline.asked);
        assertEquals(List.of(), cardless.asked);
    }
}
