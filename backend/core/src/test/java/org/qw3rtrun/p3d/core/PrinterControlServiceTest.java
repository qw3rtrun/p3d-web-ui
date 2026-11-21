package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.List;
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

        StepVerifier.create(control.uploadToCard(nobody, UUID.randomUUID(), "GHOST.GCO", false, false))
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

        StepVerifier.create(control.uploadToCard(id, UUID.randomUUID(), "OFFLINE.GCO", false, false))
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

    /**
     * The mode asked for over HTTP is the mode the uploader is started with, and so is {@code trim}
     * (#146). A flag can be added to an interface, a record and a controller and still be dropped at
     * the last hop, where nothing would disagree with it: both modes answer 200 and both announce
     * progress, so an upload silently running as ASCII - or silently sending the stored bytes - looks
     * exactly like one running as asked.
     */
    @Test
    void theChoiceOfModeReachesTheUploader() {
        var id = UUID.randomUUID();
        var manager = managerFor(directoryHolding(id));
        var asked = new AtomicReference<Boolean>();
        var trimAsked = new AtomicReference<Boolean>();
        var uploads = new SdUploader(manager, storeThatMustNotBeAsked()) {
            @Override
            public Mono<ControlOutcome> start(PrinterReactor reactor, UUID machineId, UUID fileId,
                                              String requested, boolean binary, boolean trim) {
                asked.set(binary);
                trimAsked.set(trim);
                return Mono.just(ControlOutcome.accepted(machineId));
            }
        };

        StepVerifier.create(new PrinterControlService(manager, uploads)
                        .uploadToCard(id, UUID.randomUUID(), "MBP.GCO", true, true))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(Boolean.TRUE, asked.get());
        assertEquals(Boolean.TRUE, trimAsked.get());

        StepVerifier.create(new PrinterControlService(manager, uploads)
                        .uploadToCard(id, UUID.randomUUID(), "BENCHY.GCO", false, false))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(Boolean.FALSE, asked.get());
        assertEquals(Boolean.FALSE, trimAsked.get());

        manager.release(id);
    }

    // ------------------------------------------------- the typed line's gates (issue #135)

    // a reactor owns two schedulers since #54, so whatever a gate test opened is let go of.
    private PrinterReactorManager opened;
    private UUID openedId;

    @AfterEach
    void letGoOfWhatAGateOpened() {
        if (opened != null && openedId != null) {
            opened.release(openedId);
        }
    }

    /**
     * A console on a farm that holds {@code machine} and nothing else, remembered so
     * {@link #letGoOfWhatAGateOpened()} can release the runtime a gate opened. The file store must
     * not be asked anything: typing a line has nothing to do with the store, and a stub that would
     * answer is a stub that hides a call.
     */
    private PrinterControlService consoleFor(UUID machine) {
        opened = managerFor(directoryHolding(machine));
        openedId = machine;
        return new PrinterControlService(opened, new SdUploader(opened, storeThatMustNotBeAsked()));
    }

    private static ControlOutcome.Refused typing(PrinterControlService console, UUID id, String line) {
        var outcome = console.sendRaw(id, line).block();
        assertNotNull(outcome, "sendRaw answered nothing at all for '" + line + "'");
        return refusalOf(outcome);
    }

    /**
     * Gate 1. An unknown machine is {@code ABSENT}, not an {@code IllegalArgumentException} the web
     * layer would have to classify - and it opens no runtime, the same claim the upload test above
     * makes and for the same reason: a reactor is two threads and a socket's worth of resource.
     */
    @Test
    void aLineTypedAtAMachineTheFarmDoesNotHaveIsAbsent() {
        var console = consoleFor(UUID.randomUUID());
        var nobody = UUID.randomUUID();

        var refused = typing(console, nobody, "M105");

        assertEquals(ControlOutcome.Refusal.ABSENT, refused.refusal());
        assertEquals(nobody, refused.machineId());
        assertNull(opened.runtimeOf(nobody), "a machine that does not exist got a runtime anyway");
    }

    /**
     * Gate 2, {@code rawBlock}'s. <strong>The message is passed through verbatim</strong> - it names
     * the character and its position, {@code client.ts} shows {@code reason} to whoever typed the
     * line, and rewording it here would be a second copy of the DSL's sentence to keep in step.
     */
    @Test
    void aLineCarryingACharacterThatWouldChangeTheBlockIsMalformed() {
        var id = UUID.randomUUID();
        var console = consoleFor(id);

        var semicolon = typing(console, id, "M117 hi;M112");

        assertEquals(ControlOutcome.Refusal.MALFORMED, semicolon.refusal());
        assertTrue(semicolon.reason().contains("';'"),
                "the refused character belongs in the message: " + semicolon.reason());
        assertTrue(semicolon.reason().contains("position 7"),
                "and so does where it was: " + semicolon.reason());
    }

    /** The other three {@code rawBlock} refuses: a newline, a blank line, and spec 1.3's length. */
    @Test
    void aNewlineABlankLineAndAnOverlongLineAreAllMalformed() {
        var id = UUID.randomUUID();
        var console = consoleFor(id);

        assertEquals(ControlOutcome.Refusal.MALFORMED, typing(console, id, "M105\nM112").refusal());
        assertEquals(ControlOutcome.Refusal.MALFORMED, typing(console, id, "   ").refusal());
        assertEquals(ControlOutcome.Refusal.MALFORMED,
                typing(console, id, "M117 " + "x".repeat(80)).refusal());

        // a body with no line at all reaches here as null, and is the blank line it is rather than
        // a NullPointerException the web layer would answer 500 to.
        var nothing = typing(console, id, null);
        assertEquals(ControlOutcome.Refusal.MALFORMED, nothing.refusal());
        assertTrue(nothing.reason().contains("blank"), nothing.reason());
    }

    /**
     * Gate 3a, and <strong>both halves of it in one test because one token tells them apart</strong>.
     * The send window owns the line counter, so a typed {@code N} would be framed behind the window's
     * own as {@code N12 N7 M105} - and spec 9 makes {@code N} not being first a structural error. But
     * the gate is an {@code N} word, which spec 9 defines as the letter <em>and at least one
     * digit</em>, not any token starting with {@code N}: written as an {@code equalsIgnoreCase} or a
     * prefix match it would refuse {@code NOTGCODE}, which is a perfectly good thing to type at a
     * printer to see what it says (measured against MarlinSim 2026-11-18: nothing at all).
     *
     * <p>So {@code N7 M105} refused and {@code NOTGCODE} sent is the pair, and neither half means
     * anything without the other - a gate that refuses both passes the first assertion, and no gate
     * at all passes the second. {@code NOTGCODE}'s own round trip through {@code rawBlock} is
     * {@code :gcode}'s {@code RawBlockTest}; what is asserted here is this host's decision about it.
     */
    @Test
    void aCallerSuppliedLineNumberIsMalformedAndNOTGCODEIsNot() {
        var id = UUID.randomUUID();
        var console = consoleFor(id);

        var refused = typing(console, id, "N7 M105");

        assertEquals(ControlOutcome.Refusal.MALFORMED, refused.refusal());
        assertTrue(refused.reason().contains("send window"), refused.reason());

        // as far as a unit test with no socket can follow it: past every gate to the link check,
        // which is the last one and refuses it for the machine rather than for the line.
        var notGcode = typing(console, id, "NOTGCODE");

        assertEquals(ControlOutcome.Refusal.WRONG_STATE, notGcode.refusal(),
                "NOTGCODE is not an N word and must stay sendable; it was refused as "
                        + notGcode.refusal() + ": " + notGcode.reason());
    }

    /**
     * Gate 3b. The four the transport owns, each with its own reason - and the head is matched
     * exactly, so {@code m110} and {@code M110 N0} are refused.
     */
    @Test
    void theFourCommandsTheTransportOwnsAreMalformed() {
        var id = UUID.randomUUID();
        var console = consoleFor(id);

        for (var line : List.of("M110 N0", "m110", "M112", "M28 hack.gco", "M29")) {
            var refused = typing(console, id, line);
            assertEquals(ControlOutcome.Refusal.MALFORMED, refused.refusal(), "for " + line);
            assertNotNull(refused.reason(), "a refusal has to say why, for " + line);
            assertTrue(!refused.reason().isBlank(), "for " + line);
        }
    }

    /**
     * The control on all of the above, and the reason the gates mean something: a line that is not a
     * command this host knows about is <em>not</em> refused. Measured against MarlinSim 2026-11-18,
     * {@code M851} answers {@code echo:Unknown command} and {@code NOTGCODE} answers nothing at all -
     * the machine's own refusal is the right answer from the only party that knows.
     *
     * <p>Asserted here as "it got past validation to the link check", which is as far as a unit test
     * without a socket can see; {@code TerminalIntegrationTest} is where those bytes reach a machine.
     * {@code NOTGCODE} is the one that would fail a sloppy {@code N}-word check written as a prefix.
     */
    @Test
    void aCommandThisHostDoesNotKnowIsNotRefusedForBeingUnknown() {
        var id = UUID.randomUUID();
        var console = consoleFor(id);

        for (var line : List.of("M851", "NOTGCODE", "M1100", "G0 X10")) {
            var refused = typing(console, id, line);
            assertEquals(ControlOutcome.Refusal.WRONG_STATE, refused.refusal(),
                    "'" + line + "' should have reached the link check and been refused for the"
                            + " machine not being connected, and was refused for itself: "
                            + refused.reason());
        }
    }

    /**
     * Gate 4. A well-formed line at a machine with no link is {@code WRONG_STATE} - 409 through
     * {@code Outcomes}, not 400: the line was fine and the operator was entitled to send it.
     */
    @Test
    void aWellFormedLineAtAMachineWithNoLinkIsWrongState() {
        var id = UUID.randomUUID();
        var console = consoleFor(id);

        var refused = typing(console, id, "M105");

        assertEquals(ControlOutcome.Refusal.WRONG_STATE, refused.refusal());
        assertEquals(id, refused.machineId());
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
