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
 * answers on. No translation is left here to assert: the {@code toMarlin} switch this class used to
 * hold is gone with the hand-written mirror of {@code :gcode} - a command arrives as the
 * {@code GRq} it is, and that assertion is {@code PrinterCommandTranslationTest} in
 * {@code :frontend:web-ui}.
 */
class PrinterControlServiceTest {

    /**
     * Opening a runtime happens off the caller's thread - moved here with the lookup, since which
     * thread a resource is opened on is this module's business and the web layer no longer knows
     * what a reactor is. {@code openReactor} no longer reads a database, but it is
     * {@code synchronized} and builds a resource, which an event-loop thread should not queue on.
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
        // the uploader is reached only by the two card methods, and this test calls neither.
        var control = new PrinterControlService(manager, new SdUploader(manager, null));

        control.state(id).block();

        var thread = calledOn.get();
        assertNotNull(thread, "the directory was never consulted, so the test proved nothing");
        assertTrue(thread.startsWith("boundedElastic"),
                "the runtime was opened on '" + thread + "', which is the caller's thread");

        // the runtime opened owns two threads of its own since #54, so it is let go of.
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

    // fails the test if anything asks it a question - pins the check order in SdUploader.start: the
    // link is consulted before the file, so an undialled machine must never reach a database lookup.
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
     * An upload to an id the farm has never heard of is {@code ABSENT}, and opens no runtime - the
     * second half is the one with teeth: a reactor is a resource (a terminal manager, a replay sink,
     * two threads since #54), and opening one per request naming a deleted printer is how a
     * long-running farm accumulates threads for machines it no longer has. If this failed, nothing
     * would say so - the farm would answer correctly and leak.
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
     * 409 rather than 404 at the web edge, since the printer is there - connecting it is all the
     * operator has to do. The file store is never consulted: the link is a field read and the
     * lookup a blocking round trip, so the cheap and certain check goes first.
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

        // this one does open a runtime, to say "this machine is not connected" about it, so it is
        // let go of, threads and all.
        manager.release(id);
    }

    // the reason PrinterReactorManager.knows exists separately from openReactorIfKnown: a cancel
    // must say "no such machine" without building a socket's worth of runtime for it.
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
