package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.reactivestreams.Publisher;
import reactor.test.StepVerifier;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This module's side of {@link org.qw3rtrun.p3d.api.control.PrinterControl}, and the thread it
 * answers on.
 *
 * <p><strong>There is no translation left here to assert.</strong> The {@code toMarlin} switch this
 * class used to hold - the outbound half of a hand-written mirror of `:gcode`, with
 * {@code ReplyEvents} on the inbound side - is gone with the mirror. A command arrives as the
 * {@code GRq} it is, and the one place a browser's words become Marlin's is the controller that
 * received them; those assertions are {@code PrinterCommandTranslationTest} in
 * {@code :frontend:web-ui}.
 */
class PrinterControlServiceTest {

    /**
     * Opening a runtime happens off the caller's thread.
     *
     * <p>This assertion used to live in the frontend module, because the controllers did the
     * scheduling. It moved here with the lookup: which thread a resource is opened on is this
     * module's business, and the web layer no longer knows what a reactor is.
     *
     * <p>{@code openReactor} no longer reads a database - {@link PrinterDirectory} answers that -
     * but it is {@code synchronized} and builds a resource, and an event-loop thread should not be
     * queueing on a monitor when several requests arrive for one printer.
     */
    @Test
    void openingARuntimeRunsOffTheCallersThread() {
        var calledOn = new AtomicReference<String>();
        var directory = new PrinterDirectory() {
            @Override
            public Optional<Entry> find(UUID id) {
                calledOn.set(Thread.currentThread().getName());
                return super.find(id);
            }
        };
        var id = UUID.randomUUID();
        directory.onRegistered(new MRegisteredEvent(id, "a printer", "localhost", 8099,
                MachineProfile.unknown(), MachineSpec.undeclared(),
                MachineConnectionState.OFFLINE, LocalDateTime.now()));
        var manager = new PrinterReactorManager(directory, PrinterState::new, event -> {
        });
        // The uploader is reached only by the two card methods, and this test calls neither.
        var control = new PrinterControlService(manager, new SdUploader(manager, null));

        control.state(id).block();

        var thread = calledOn.get();
        assertNotNull(thread, "the directory was never consulted, so the test proved nothing");
        assertTrue(thread.startsWith("boundedElastic"),
                "the runtime was opened on '" + thread + "', which is the caller's thread");

        // The runtime that was opened owns two threads of its own since #54, so it is let go of.
        manager.release(id);
    }

    // ------------------------------------------------- the refusals (issue #65)

    /** A directory that has been told about {@code known} and about nothing else. */
    private PrinterDirectory directoryHolding(UUID known) {
        var directory = new PrinterDirectory();
        directory.onRegistered(new MRegisteredEvent(known, "a printer", "localhost", 8099,
                MachineProfile.unknown(), MachineSpec.undeclared(),
                MachineConnectionState.OFFLINE, LocalDateTime.now()));
        return directory;
    }

    private PrinterReactorManager managerFor(PrinterDirectory directory) {
        return new PrinterReactorManager(directory, PrinterState::new, event -> {
        });
    }

    /**
     * A file store that fails the test if anything asks it a question.
     *
     * <p>This is the whole of how the check order in {@code SdUploader.start} is pinned: the link
     * is consulted before the file, so a machine nothing has dialled must never reach a database
     * lookup. Nothing else can state that - the two checks produce different refusals, but a
     * refusal says nothing about what was consulted on the way to it.
     */
    private GcodeFileService storeThatMustNotBeAsked() {
        return new GcodeFileService() {
            @Override
            public Publisher<GcodeFile> store(String name, Publisher<ByteBuffer> content) {
                return fail("the file store was written to");
            }

            @Override
            public Optional<GcodeFile> fetch(UUID id) {
                return fail("the file store was consulted for a machine that is not connected");
            }

            @Override
            public Stream<GcodeFile> fetchAll() {
                return fail("the file store was listed");
            }

            @Override
            public Publisher<ByteBuffer> read(UUID id) {
                return fail("the file store was read");
            }

            @Override
            public void delete(UUID id) {
                fail("the file store was deleted from");
            }
        };
    }

    private static ControlOutcome.Refused refusalOf(ControlOutcome outcome) {
        assertTrue(outcome instanceof ControlOutcome.Refused,
                "expected a refusal and got " + outcome);
        return (ControlOutcome.Refused) outcome;
    }

    /**
     * An upload to an id the farm has never heard of is {@code ABSENT}, and opens no runtime.
     *
     * <p><strong>The second half is the one with teeth.</strong> A reactor is a resource - a
     * terminal manager, a replay sink and, since #54, two threads - and the obvious way to answer
     * this question used to be to open one and discover it pointed at nowhere. Opening one per
     * request naming a deleted printer is how a long-running farm accumulates threads for machines
     * it no longer has. If this failed, nothing would say so: the farm would answer correctly and
     * leak.
     */
    @Test
    void anUploadToAMachineTheFarmDoesNotHaveIsAbsentAndOpensNoRuntime() {
        var directory = directoryHolding(UUID.randomUUID());
        var manager = managerFor(directory);
        var control = new PrinterControlService(manager,
                new SdUploader(manager, storeThatMustNotBeAsked()));
        var nobody = UUID.randomUUID();

        StepVerifier.create(control.uploadToCard(nobody, UUID.randomUUID(), "GHOST.GCO"))
                .assertNext(outcome -> {
                    var refused = refusalOf(outcome);
                    assertEquals(ControlOutcome.Refusal.ABSENT, refused.refusal());
                    assertEquals(nobody, refused.machineId(), "the refusal names the machine asked");
                })
                .verifyComplete();

        assertNull(manager.runtimeOf(nobody), "a machine that does not exist got a runtime anyway");
    }

    /**
     * An upload to a known machine nothing has dialled is {@code WRONG_STATE}, and asks no store.
     *
     * <p>409 rather than 404 at the web edge, because the printer is there: connecting it is the
     * whole of what the operator has to do, and telling them it does not exist would send them
     * looking for the wrong thing.
     *
     * <p><strong>And the file store is never consulted</strong>, which is the check order from
     * {@code SdUploader.start} stated as a test: the link is a field read and the lookup is a
     * blocking database round trip, so the cheap and certain one goes first. A farm whose printers
     * are mostly unplugged is the normal case.
     */
    @Test
    void anUploadToAMachineThatWasNeverDialledIsWrongStateAndAsksNoStore() {
        var id = UUID.randomUUID();
        var directory = directoryHolding(id);
        var manager = managerFor(directory);
        var control = new PrinterControlService(manager,
                new SdUploader(manager, storeThatMustNotBeAsked()));

        StepVerifier.create(control.uploadToCard(id, UUID.randomUUID(), "OFFLINE.GCO"))
                .assertNext(outcome -> {
                    var refused = refusalOf(outcome);
                    assertEquals(ControlOutcome.Refusal.WRONG_STATE, refused.refusal());
                    assertEquals(id, refused.machineId());
                })
                .verifyComplete();

        // This one does open a runtime - it has to, to be able to say "this machine is not
        // connected" about this machine - so it is let go of, threads and all.
        manager.release(id);
    }

    /**
     * Stopping a transfer on a machine the farm does not have is {@code ABSENT}, and opens nothing.
     *
     * <p>The reason {@code PrinterReactorManager.knows} exists separately from
     * {@code openReactorIfKnown}: a cancel must be able to say "there is no such machine" without
     * building a socket's worth of runtime to answer a question about a transfer that is not
     * running. A failure of the second assertion is the opposite of {@code release}'s whole point.
     */
    @Test
    void aCancelForAMachineTheFarmDoesNotHaveIsAbsentAndOpensNoRuntime() {
        var directory = directoryHolding(UUID.randomUUID());
        var manager = managerFor(directory);
        var control = new PrinterControlService(manager,
                new SdUploader(manager, storeThatMustNotBeAsked()));
        var nobody = UUID.randomUUID();

        StepVerifier.create(control.cancelCardUpload(nobody))
                .assertNext(outcome -> {
                    var refused = refusalOf(outcome);
                    assertEquals(ControlOutcome.Refusal.ABSENT, refused.refusal());
                    assertEquals(nobody, refused.machineId());
                })
                .verifyComplete();

        assertNull(manager.runtimeOf(nobody), "a cancel built a runtime to answer a question");
    }
}
