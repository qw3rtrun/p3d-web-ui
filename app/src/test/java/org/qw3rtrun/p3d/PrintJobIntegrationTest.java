package org.qw3rtrun.p3d;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The print queue - issue #43's feature 7, through {@code /api-jobs}, and issue #51.
 *
 * <p><strong>The state table is deliberately not here.</strong> Which transitions are legal is
 * {@code JobServiceTest}'s subject in {@code :backend:core}, where it is driven against the entity
 * that owns it; restating it over HTTP would be a second copy of a table that already has one, and
 * the second copy is the one that goes stale. What is left for this level is everything that only
 * exists once the modules are assembled:
 *
 * <ul>
 *   <li><strong>a refusal is a status and a legible body.</strong> {@code JobOutcome} is a sealed
 *       value precisely so that the web layer does not have to guess from an exception's class, and
 *       whether that mapping is right - 409 for "the job is not where you thought", 404 for "there
 *       is no such job or file" - can only be asked of a running server;</li>
 *   <li><strong>starting a job feeds the file to the machine.</strong> That is the half no unit test
 *       in either module spans: {@code jobs} publishes a transition, {@code core} reads the file and
 *       hands it to the transport, and the transport numbers, frames and writes it. The assertion is
 *       the G-code that reached {@link MockPrinter};</li>
 *   <li><strong>a feed at {@code BACKGROUND} is preempted and then carries on where it was.</strong>
 *       This is the one with teeth and the reason the issue exists. It spans the priority queue in
 *       {@code :backend:terminal}, the send window in {@code :gcode} and the feeder in
 *       {@code :backend:core}, and no unit test in any of them can see it, because it needs a real
 *       file, a real window and a second source arriving mid-stream.</li>
 * </ul>
 *
 * <p><strong>There is no UI for any of this yet</strong>, so these endpoints are the whole contract
 * and nothing here is a stand-in for a page test.
 *
 * <h2>Two things about the farm that shape every test below</h2>
 *
 * <p><strong>The scheduler is always running.</strong> {@code FarmScheduler} assigns and starts any
 * {@code QUEUED} job the moment a machine is {@code IDLE} and free - on every job transition and on
 * a five-second sweep. A test that queued a job against a connected machine and then posted
 * {@code /assign} would be racing it and would get a 409 for its trouble. So a job here is queued
 * <em>pinned to its machine while that machine is still offline</em>, which leaves it queued with a
 * stated reason, and it is moved by hand before the machine is dialled. The queue-only tests pin to
 * a machine the farm has never heard of, so no scheduler pass can take them whatever else the suite
 * has left connected.
 *
 * <p><strong>Jobs outlive the test that made them.</strong> {@code DELETE /api-jobs/{id}} cancels a
 * job and deliberately does not remove the row - a cancelled job is a record of something somebody
 * decided - and the whole module shares one database. A job left {@code QUEUED} would therefore be
 * picked up by the next test's printer. {@link #cancelWhatWasQueued()} is what stops that, and it is
 * here rather than in {@code FarmIntegrationTest} because no other feature creates one.
 */
class PrintJobIntegrationTest extends FarmIntegrationTest {

    /** What a cancelled or failed print leaves the machine in - {@code JobFeeder.coolDown}. */
    private static final List<String> COOL_DOWN =
            List.of("M107", "M104 S0", "M140 S0", "M83", "G1 E-2 F2400");

    private final List<UUID> queued = new ArrayList<>();

    /**
     * Takes every job this test queued off the queue.
     *
     * <p>Not optional, and not tidiness. A {@code QUEUED} job is work the farm is actively looking
     * for a printer for, so one left behind is handed to the first {@code IDLE} machine any later
     * test registers - which would start a print nobody asked for, against a mock asserting on an
     * exact list of commands. Cancelling is the only way to take one out of consideration, since
     * the row stays.
     */
    @AfterEach
    void cancelWhatWasQueued() {
        for (var id : queued) {
            try {
                farm.delete().uri("/api-jobs/{id}", id).exchange();
            } catch (RuntimeException e) {
                // A job that already ended answers 409. A failed cleanup must not mask the failure
                // that matters.
            }
        }
        queued.clear();
    }

    // ---------------------------------------------------------------- the queue, with no machine

    /**
     * Queueing a file answers with the job it became, and the queue can be read back.
     *
     * <p>Pinned to a machine that does not exist, so that it stays where it is put: this test is
     * about the record and the listing, and a scheduler quietly starting it would turn every field
     * below into a race. {@code reason} on a queued job carries why it has not been given a machine,
     * which is a different sentence from why a job ended - see {@code JobEntity.describe}.
     */
    @Test
    void queueingAFileAnswersWithTheJobItBecame() {
        var file = GcodeFixture.cura("queued").named("queued.gcode");
        var stored = upload(file);
        var pinnedToNothing = UUID.randomUUID();

        var job = queue(idOf(stored), pinnedToNothing);

        assertEquals("QUEUED", job.get("state"));
        assertEquals(stored.get("id"), job.get("fileId"));
        assertEquals("queued.gcode", job.get("fileName"), "the name is kept on the job, not looked up");
        assertNull(job.get("machineId"), "queueing chooses a file, not a printer");
        assertNull(job.get("startedAt"));
        assertNull(job.get("finishedAt"));
        assertEquals(pinnedToNothing.toString(), requiresOf(job).get("machineId"),
                "what the job asked for has to survive the round trip, or a pin means nothing");

        var id = idOf(job);
        assertEquals(job.get("state"), fetch(id).get("state"), "the single job and the one just made");
        assertTrue(jobsInTheQueue().stream().anyMatch(j -> id.equals(idOf(j))),
                "the job is not in the queue a browser would draw: " + jobsInTheQueue());
    }

    /**
     * A job names a file the farm holds, and one that names nothing is a 404.
     *
     * <p>404 and not 409: {@code JobOutcome.Refused} carries no state here, because there is no job
     * to have one, and that is exactly what the controller keys on rather than guessing from an
     * exception class. A 500 would be the alternative and is what this shape exists to avoid.
     */
    @Test
    void queueingAFileTheFarmDoesNotHoldIsNotFound() {
        var nothing = UUID.randomUUID();

        var refusal = json(farm.post().uri("/api-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"fileId\":\"" + nothing + "\"}")
                .exchange()
                .expectStatus().isNotFound());

        assertNull(refusal.get("state"), "there is no job, so there is no state to report");
        assertTrue(((String) refusal.get("reason")).contains(nothing.toString()),
                "the body must say which file: " + refusal);
    }

    /**
     * A move the job cannot make is a 409 saying where it actually is.
     *
     * <p>The three fields are the whole of what a browser needs to recover: where it is, what was
     * asked, and a sentence to show. The common cause is an ordinary race - somebody clicks Start as
     * the scheduler does - so the right answer is to tell them the truth and let the page re-read.
     *
     * <p>Which moves are legal is not asserted here; one that is not is enough to pin the mapping.
     */
    @Test
    void aMoveTheJobCannotMakeIsAConflictSayingWhereItIs() {
        var stored = upload(GcodeFixture.cura("refused").named("refused.gcode"));
        var job = idOf(queue(idOf(stored), UUID.randomUUID()));

        var refusal = json(farm.post().uri("/api-jobs/{id}/start", job)
                .exchange()
                .expectStatus().isEqualTo(409));

        assertEquals(job.toString(), refusal.get("jobId"));
        assertEquals("QUEUED", refusal.get("state"), "where the job actually is");
        assertEquals("RUNNING", refusal.get("attempted"), "what was asked of it");
        assertEquals("a job that is QUEUED cannot become RUNNING", refusal.get("reason"),
                "a sentence a user could be shown, not a stack trace");
        assertEquals("QUEUED", fetch(job).get("state"), "a refused move must not move anything");
    }

    /**
     * A job the farm does not have is a 404 at every verb, and never a 500.
     *
     * <p>All six, because a browser's client parses the body of each of them and a missing job is
     * the ordinary consequence of two tabs being open on the same queue.
     */
    @Test
    void aJobTheFarmDoesNotHaveIsNotFoundAtEveryVerb() {
        var unknown = UUID.randomUUID();

        farm.get().uri("/api-jobs/{id}", unknown).exchange().expectStatus().isNotFound();
        farm.post().uri("/api-jobs/{id}/start", unknown).exchange().expectStatus().isNotFound();
        farm.post().uri("/api-jobs/{id}/pause", unknown).exchange().expectStatus().isNotFound();
        farm.post().uri("/api-jobs/{id}/resume", unknown).exchange().expectStatus().isNotFound();
        farm.delete().uri("/api-jobs/{id}", unknown).exchange().expectStatus().isNotFound();
        farm.post().uri("/api-jobs/{id}/assign", unknown)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"machineId\":\"" + UUID.randomUUID() + "\"}")
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * The queue can be read whole or for one machine.
     *
     * <p>Two different pages ask these: a farm-wide queue and a printer's own panel. Asserted by
     * containment rather than by equality, because every job the suite has ever queued is still a
     * row - see the class note on why that is deliberate.
     */
    @Test
    void theQueueCanBeReadWholeOrForOneMachine() {
        var mine = register("holds a job", machine(MockPrinter.marlin()));
        var other = register("holds another", machine(MockPrinter.marlin()));
        var stored = idOf(upload(GcodeFixture.cura("listed").named("listed.gcode")));

        var here = idOf(queue(stored, mine));
        var there = idOf(queue(stored, other));
        assign(here, mine);
        assign(there, other);

        var forMine = jobsOf(mine).stream().map(FarmIntegrationTest::idOf).toList();
        assertEquals(List.of(here), forMine, "a printer's panel shows that printer's work: " + forMine);

        var whole = jobsInTheQueue().stream().map(FarmIntegrationTest::idOf).toList();
        assertTrue(whole.containsAll(List.of(here, there)),
                "the farm-wide queue is missing one of them: " + whole);
    }

    // ---------------------------------------------------------------- what reaches the machine

    /**
     * A started job's file reaches the machine, block for block, and the job ends {@code COMPLETED}.
     *
     * <p>The baseline. What is asserted is the whole command log after the opening handshake, so a
     * block invented, duplicated, reordered or dropped anywhere between the file store and the
     * socket fails it - which is the failure this repository has shipped three times with every
     * other test passing.
     */
    @Test
    void aStartedJobFeedsItsWholeFileToTheMachine() {
        theWholeFileReachesTheMachine(MockPrinter.marlin(), "fed");
    }

    /**
     * The same, against a firmware that acknowledges with a bare {@code ok}.
     *
     * <p><strong>The acknowledgement dialect is the configuration axis a print job has.</strong> It
     * decides how much the host may have outstanding: a machine reporting {@code ok P15 B3} lets the
     * window hold three blocks, and one that reports no buffer counts holds it at the single block
     * {@code AdvancedOkRs(1, 1, null)} starts it at. A job is a stream of tens of thousands of
     * blocks through exactly that gate, so "it prints to the end either way" is a real claim and the
     * file that comes out has to be identical.
     *
     * <p>The other profiles change nothing here and are not driven: an extruder count is not
     * consulted by a feed, a card is not involved in printing from the host, and a machine that
     * never answers is {@code ConnectivityIntegrationTest}'s subject.
     */
    @Test
    void aJobPrintsToTheEndOnAFirmwareThatReportsNoBufferCounts() {
        theWholeFileReachesTheMachine(MockPrinter.legacyOk(), "fed-legacy");
    }

    private void theWholeFileReachesTheMachine(MockPrinter.Profile profile, String mark) {
        var printer = machine(profile);
        var id = register(profile.label(), printer);
        var file = GcodeFixture.unsliced(mark).named(mark + ".gcode").withBodyLines(6);

        var job = startOn(id, printer, file);

        await(() -> "job " + job + " to finish. It is " + stateOf(job)
                        + " and the machine received " + printer.commands(),
                () -> "COMPLETED".equals(stateOf(job)));

        assertEquals(file.blocks(), printer.commands(),
                "every block of the file, in order, and nothing else");

        var finished = fetch(job);
        assertEquals(id.toString(), finished.get("machineId"));
        assertNotNull(finished.get("startedAt"));
        assertNotNull(finished.get("finishedAt"));
    }

    /**
     * A print is preempted by an interactive command and then carries on where it was.
     *
     * <p><strong>This is the assertion the issue exists for, and the three things it proves are
     * three different claims.</strong> A job feeds at {@code BACKGROUND} and an interactive command
     * is queued at {@code REGULAR}, so:
     *
     * <ul>
     *   <li><strong>it is preempted</strong> - {@code M105} takes the very next slot on the wire,
     *       rather than waiting behind the rest of the file. On a real print that is the difference
     *       between a temperature query answering now and answering in six hours;</li>
     *   <li><strong>the job is not restarted</strong> - no block appears twice;</li>
     *   <li><strong>and it is not dropped</strong> - no block is missing. The queue hands its
     *       remaining demand back to the source it took it from, so the feed continues at the block
     *       it had reached.</li>
     * </ul>
     *
     * <p>All three fall out of one assertion: remove the {@code M105} from what the machine received
     * and what is left must be the file, exactly, in order.
     *
     * <h2>How the timing is made honest</h2>
     *
     * <p>A print against a mock that answers instantly is over before the next line of the test
     * runs, so there would be nothing to preempt. {@link MockPrinter#holdRepliesFrom(String)} stops
     * the machine answering from a named block onwards, and the send window does the rest: the host
     * may not have more outstanding than the printer's buffer allows, so the conversation stops
     * within a few blocks and stays stopped. The sample taken below is therefore a number that
     * cannot move, which is asserted rather than assumed.
     *
     * <p>The ordering of the two sources is not a race either. {@code PrinterReactor.handle}
     * applies a command on the aggregate's own executor and completes its {@code Mono} afterwards,
     * so a 200 from {@code /report-temp} means the {@code M105} is already in the priority queue -
     * before anything is released, and therefore before the job can be given another unit of demand.
     */
    @Test
    void aFeedingJobIsPreemptedByAnInteractiveCommandAndResumesWhereItWas() {
        var printer = machine(MockPrinter.marlin());
        var id = register("preempted", printer);
        var file = GcodeFixture.unsliced("preempt").named("preempt.gcode").withBodyLines(12);
        var blocks = file.blocks();
        printer.holdRepliesFrom(blocks.get(5));

        var job = startOn(id, printer, file);
        var onTheWire = stalled(printer, blocks.get(5));

        command(id, "/report-temp").expectStatus().isOk();
        printer.answerFreely();

        await(() -> "job " + job + " to finish. It is " + stateOf(job)
                        + " and the machine received " + printer.commands(),
                () -> "COMPLETED".equals(stateOf(job)));

        var received = printer.commands();
        assertEquals(1, received.stream().filter("M105"::equals).count(),
                "the interactive command was asked for once: " + received);
        assertEquals(onTheWire, received.indexOf("M105"),
                "M105 should have taken the very next slot on the wire and did not: " + received);
        assertTrue(received.indexOf("M105") < received.size() - 1,
                "it overtook nothing - the file was already finished: " + received);
        assertEquals(blocks, received.stream().filter(c -> !c.equals("M105")).toList(),
                "with the interactive command taken out, what reached the machine must be the file"
                        + " exactly: a repeat means the job restarted and a gap means blocks were"
                        + " dropped");
    }

    /**
     * Pausing records where the job got to, and resuming goes on from there.
     *
     * <p><strong>The checkpoint is the mechanism and it is worth asserting directly.</strong>
     * {@code JobFeeder} takes one exact reading at the moment it stops - the timer's checkpoints are
     * up to a second stale - and a resume seeks to whatever was written down. A number that is even
     * slightly behind means the machine extrudes over what it has already printed, which was
     * observed on a live run before the reading was made exact.
     *
     * <p>So: stop the machine answering, pause, and the number on the job must be exactly the number
     * of blocks that reached the machine. Then let it go, resume, and the file that comes out has to
     * be the file - the same assertion as the preemption test, and for the same reason.
     */
    @Test
    void pausingRecordsWhereTheJobGotToAndResumingContinuesFromThere() {
        var printer = machine(MockPrinter.marlin());
        var id = register("paused", printer);
        var file = GcodeFixture.unsliced("paused").named("paused.gcode").withBodyLines(12);
        var blocks = file.blocks();
        printer.holdRepliesFrom(blocks.get(5));

        var job = startOn(id, printer, file);
        var onTheWire = stalled(printer, blocks.get(5));

        farm.post().uri("/api-jobs/{id}/pause", job).exchange().expectStatus().isOk();

        var paused = fetch(job);
        assertEquals("PAUSED", paused.get("state"));
        assertEquals((long) onTheWire, number(progressOf(paused).get("blocksSent")),
                "the reading taken at the pause has to be the blocks that actually went out");
        assertEquals(file.sizeBytes(), number(progressOf(paused).get("bytesTotal")),
                "the denominator a progress figure is divided by");

        printer.answerFreely();
        farm.post().uri("/api-jobs/{id}/resume", job).exchange().expectStatus().isOk();

        await(() -> "job " + job + " to finish after resuming. It is " + stateOf(job)
                        + " and the machine received " + printer.commands(),
                () -> "COMPLETED".equals(stateOf(job)));

        assertEquals(blocks, printer.commands(),
                "resumed at the block it had reached: nothing sent twice and nothing skipped");
    }

    /**
     * Cancelling a print stops it and leaves the machine cool, rather than finishing the file.
     *
     * <p>Two halves, and the second is why the cool-down is queued at {@code REGULAR} rather than
     * appended: a cancellation that waited behind the rest of a six-hour print would turn the heaters
     * off six hours after somebody asked. So the five blocks go in <em>front</em> of what is left of
     * the file, and what is left of the file never goes at all.
     *
     * <p>It does not home, park or disable the steppers, and this asserts that too by asserting the
     * exact list: homing drags the nozzle across the part and dropping the steppers drops the Z axis
     * on a machine with no brake. Both are recorded as debt in {@code JobFeeder.coolDown} rather
     * than guessed at, and a change that started doing either should have to come through here.
     */
    @Test
    void cancellingAPrintStopsItAndCoolsTheMachineDown() {
        var printer = machine(MockPrinter.marlin());
        var id = register("cancelled", printer);
        var file = GcodeFixture.unsliced("cancelled").named("cancelled.gcode").withBodyLines(12);
        var blocks = file.blocks();
        printer.holdRepliesFrom(blocks.get(5));

        var job = startOn(id, printer, file);
        var onTheWire = stalled(printer, blocks.get(5));

        farm.delete()
                .uri(uri -> uri.path("/api-jobs/" + job).queryParam("reason", "the bed came loose").build())
                .exchange()
                .expectStatus().isOk();
        printer.answerFreely();

        await(() -> "the machine to be cooled down. It received " + printer.commands(),
                () -> printer.sent("G1 E-2"));
        quiet();

        var received = printer.commands();
        assertEquals(COOL_DOWN, received.subList(onTheWire, received.size()),
                "the cool-down must go in front of what is left of the file, and nothing else"
                        + " after it: " + received);
        assertFalse(received.contains(blocks.getLast()),
                "the rest of the file was sent anyway, so the cancellation only stopped the job on"
                        + " paper: " + received);

        var cancelled = fetch(job);
        assertEquals("CANCELLED", cancelled.get("state"));
        assertEquals("the bed came loose", cancelled.get("reason"),
                "why somebody stopped it is kept on the job");
    }

    // ---------------------------------------------------------------- when blocks are lost

    /**
     * A job whose blocks the transport abandoned fails, rather than reporting {@code COMPLETED} -
     * issue #60.
     *
     * <p><strong>This is a wrong outcome and not a wrong number.</strong> The firmware rejects
     * {@code G21}, {@code G90} and {@code G28} because its line counter is where a previous host
     * session left it; the host cannot answer a {@code Resend:} for a block it has never framed,
     * so {@code CommandProcessor.resynchronise} starts the numbering again and drops what it was
     * holding; the rest of the file then prints perfectly. Before this issue the job reported
     * {@code COMPLETED}, and a print that silently skipped its {@code G28} homed nowhere.
     *
     * <p><strong>Why no test caught it.</strong> A fresh {@link MockPrinter} starts at line 0 and a
     * fresh host numbers from 1, so the two agreed by construction and the case could not arise -
     * see {@link MockPrinter#checkLineNumbersFrom(String, int)}, which is the fixture that makes a
     * machine able to outlive a host session, and whose every reply line is measured.
     *
     * <p><strong>Why the drift is armed mid-file rather than by reconnecting.</strong> On a
     * reconnect this host opens with {@code M110 N0} and waits for its acknowledgement before any
     * real block is pulled - the send window starts at one outstanding block, so it has to - and
     * that genuinely repairs the sequence. Measured, on the wire, by {@code ResendOnReconnectProbe}:
     *
     * <pre>
     *   -&gt; N1 M110 N0*124
     *   &lt;- ok N1 P15 B3
     *   -&gt; N1 G21*27
     * </pre>
     *
     * <p>So prevention at the opening is not the missing piece; it is already there and it already
     * works. What it cannot prevent is the two ends disagreeing <em>after</em> the opening - a lost
     * {@code ok}, a half-line the firmware buffered from the previous session, an {@code Error:} on
     * a line the window has moved past - and every one of those arrives at
     * {@code resynchronise} the same way. One counter that is already pushed when the file's first
     * block arrives stands for all of them, and what goes over the wire below it is exactly what
     * the simulator produced.
     *
     * <p>The three assertions are three different claims: the blocks really were lost, the job says
     * so, and it says why in words an operator can act on.
     *
     * <h2>Why the machine is also stopped mid-file</h2>
     *
     * <p><strong>Without the gate this test is a coin flip, and it was measured as one: four passes
     * in nine runs.</strong> The abandonment crosses two {@code publishOn(executor)} hops onto the
     * shared {@code Schedulers.single()} — {@code PrinterReactor.gEvent}'s and {@code updates()}'
     * — while the feed's completion arrives independently on the netty thread. Against a mock that
     * answers instantly an eighteen-block file is over in about ten milliseconds, so the two race,
     * and whichever lands first decides whether the job reports {@code FAILED} or {@code COMPLETED}.
     * From a failing run:
     *
     * <pre>
     *   52.012 nio-4    &lt;- Resend: 15
     *   52.013 nio-4    OUT: … abandoning 3 block(s)
     *   52.017 single-1 PrinterState: 3 block(s) … were abandoned        (4 ms later)
     *   52.022 single-1 PrinterReactor: &lt;- EventMessage[BlocksAbandoned] (9 ms later)
     *   52.022 nio-4    JobFeeder: job … sent its last block (18 in all)
     * </pre>
     *
     * <p><strong>That race is real but it is at fixture scale, and it is not what this test is
     * for.</strong> On a print of any length the blocks are abandoned thousands of blocks before
     * the file ends; the window in which the two can swap is the last few milliseconds of a job.
     * It is recorded as debt in the {@code spring-backend-dev} skill with the measurement above,
     * and closing it properly means making the fact synchronously available to the completion path
     * rather than racing it — which touches the same code as
     * [#62](https://github.com/qw3rtrun/p3d-web-ui/issues/62) and belongs with it.
     *
     * <p>So {@link MockPrinter#holdRepliesFrom(String)} stops the machine answering partway through
     * the file — §4.4's tool, and the same one the preemption and pause tests use. The file then
     * <em>cannot</em> run out, so the only terminal state this job can reach is the one the
     * abandonment puts it in, and the assertion is about the outcome rather than about the
     * scheduler. A regression leaves the job {@code RUNNING} and the wait says so.
     */
    @Test
    void aJobWhoseBlocksWereAbandonedFailsRatherThanReportingCompleted() {
        var printer = machine(MockPrinter.marlin());
        var id = register("abandoned blocks", printer);
        var file = GcodeFixture.unsliced("abandoned").named("abandoned.gcode").withBodyLines(12);
        var blocks = file.blocks();

        // From the file's first block on, this is a machine whose counter stands at 14 - which is
        // where a machine that outlived a host session stands. The opening handshake is answered
        // normally: M110 N0, M115 and M155 S1 are not blocks out of a file.
        printer.checkLineNumbersFrom(blocks.getFirst(), 14);
        // And it stops answering well after the resynchronisation, so the file cannot finish
        // before the abandonment has been acted on. See the note above.
        printer.holdRepliesFrom(blocks.get(8));

        var job = startOn(id, printer, file);

        await(() -> "job " + job + " to fail, because three of its blocks were abandoned by the"
                        + " send window and never reached the machine. It is " + stateOf(job)
                        + " and the machine received " + printer.commands(),
                () -> "FAILED".equals(stateOf(job)));

        var finished = fetch(job);
        assertFalse(printer.commands().contains(blocks.getFirst()),
                "the premise of this test is that the machine never ran the file's first block,"
                        + " and it did: " + printer.commands());
        assertTrue(((String) finished.get("reason")).contains("abandoned"),
                "a failed job has to say what went wrong in words an operator can act on: "
                        + finished.get("reason"));

        printer.answerFreely();
    }

    /**
     * A resend the host <em>can</em> answer costs the job nothing - the control for the test above.
     *
     * <p>Without this, "fail the job when a {@code Resend:} arrives" would pass the test above and
     * be badly wrong: a corrupted block is ordinary traffic on a serial line, the window holds
     * every block until the {@code ok} that accepts it, and replaying one is the mechanism spec
     * 8.5 exists for. Measured in the same probe run as the rejection, as the control it is:
     *
     * <pre>
     *   &gt;&gt; N2 G90*19  &lt;&lt; Error:checksum mismatch, Last Line: 1
     *                 &lt;&lt; Resend: 2
     *                 &lt;&lt; ok
     *   &gt;&gt; N2 G90*18  &lt;&lt; ok N2 P15 B3
     * </pre>
     *
     * <p>The push is the firmware's half of that, put to a session whose window is holding the
     * block being asked for - the same shape as
     * {@code ConnectivityIntegrationTest.aStrayAcknowledgementDoesNotShiftTheSendWindow}, with a
     * job behind it. The block goes out a second time, byte for byte, and the job finishes.
     */
    @Test
    void aResendTheHostCanAnswerDoesNotFailTheJob() {
        var printer = machine(MockPrinter.marlin());
        var id = register("recoverable resend", printer);
        var file = GcodeFixture.unsliced("replayed").named("replayed.gcode").withBodyLines(12);
        var blocks = file.blocks();
        printer.holdRepliesFrom(blocks.get(5));

        var job = startOn(id, printer, file);
        stalled(printer, blocks.get(5));

        var block = printer.blocks().getLast();
        var number = MockPrinter.number(block);
        assertNotNull(number, "the farm numbers every block it sends; this one is " + block);
        printer.push("Resend: " + number);

        await(() -> "block " + block + " to be replayed byte for byte. The machine received "
                        + printer.blocks(),
                () -> Collections.frequency(printer.blocks(), block) == 2);
        printer.answerFreely();

        await(() -> "job " + job + " to finish. It is " + stateOf(job)
                        + " and the machine received " + printer.commands(),
                () -> isOver(stateOf(job)));

        assertEquals("COMPLETED", fetch(job).get("state"),
                "the window held the block the firmware asked for and replayed it, so nothing was"
                        + " lost and the job is not a failure: " + printer.commands());
    }

    /** Whether a job has reached a state it can never leave - {@code JobState.isTerminal}. */
    private static boolean isOver(String state) {
        return List.of("COMPLETED", "FAILED", "CANCELLED").contains(state);
    }

    // ---------------------------------------------------------------- driving one job

    /**
     * Queues a file for a machine, gives it to that machine, dials it, and starts it.
     *
     * <p>The order is what keeps the scheduler out of the way, and it is the honest order anyway:
     * the job is pinned while the machine is still offline, so no pass can take it; it is assigned
     * by hand, which takes it out of {@code queued()} for good; and only then is the machine dialled
     * and the job started. Every step is the endpoint a browser would call.
     */
    private UUID startOn(UUID machineId, MockPrinter printer, GcodeFixture file) {
        var stored = idOf(upload(file));
        var job = idOf(queue(stored, machineId));
        assign(job, machineId);

        connectAndSettle(machineId, printer);

        var running = json(farm.post().uri("/api-jobs/{id}/start", job)
                .exchange().expectStatus().isOk());
        assertEquals("RUNNING", running.get("state"), "the job did not start: " + running);
        return job;
    }

    // ---------------------------------------------------------------- the endpoints, as a browser

    /** Queues a file for one named machine. Pinned, so that no scheduler pass can move it. */
    private Map<String, Object> queue(UUID fileId, UUID pinnedTo) {
        var job = json(farm.post().uri("/api-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"fileId\":\"" + fileId + "\","
                        + "\"requires\":{\"machineId\":\"" + pinnedTo + "\"}}")
                .exchange()
                .expectStatus().isOk());
        queued.add(idOf(job));
        return job;
    }

    private void assign(UUID job, UUID machineId) {
        var assigned = json(farm.post().uri("/api-jobs/{id}/assign", job)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"machineId\":\"" + machineId + "\"}")
                .exchange()
                .expectStatus().isOk());
        assertEquals("ASSIGNED", assigned.get("state"), "the job was not assigned: " + assigned);
        assertEquals(machineId.toString(), assigned.get("machineId"));
    }

    private Map<String, Object> fetch(UUID job) {
        return json(farm.get().uri("/api-jobs/{id}", job).exchange().expectStatus().isOk());
    }

    private String stateOf(UUID job) {
        return (String) fetch(job).get("state");
    }

    private List<Map<String, Object>> jobsInTheQueue() {
        return jsonList(farm.get().uri("/api-jobs").exchange().expectStatus().isOk());
    }

    private List<Map<String, Object>> jobsOf(UUID machineId) {
        return jsonList(farm.get()
                .uri(uri -> uri.path("/api-jobs").queryParam("machineId", machineId).build())
                .exchange().expectStatus().isOk());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> progressOf(Map<String, Object> job) {
        var progress = job.get("progress");
        assertNotNull(progress, "progress is never null - a job that never ran carries an empty one");
        return (Map<String, Object>) progress;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiresOf(Map<String, Object> job) {
        var requires = job.get("requires");
        assertNotNull(requires, "requires is never null - a job that asks for nothing carries any()");
        return (Map<String, Object>) requires;
    }
}
