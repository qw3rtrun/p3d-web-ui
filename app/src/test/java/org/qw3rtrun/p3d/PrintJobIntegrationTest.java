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
 * The print queue - issue #43's feature 7, through {@code /api-jobs}, and issue #51. The state
 * table is deliberately not here: which transitions are legal is {@code JobServiceTest}'s subject
 * in {@code :backend:core}, driven against the entity that owns it, and restating it over HTTP
 * would be a second copy that goes stale. What is left for this level is what only exists once the
 * modules are assembled: a refusal is a status and a legible body ({@code JobOutcome} is sealed
 * precisely so the web layer does not guess from an exception's class - 409 for "not where you
 * thought", 404 for "no such job or file" - and whether that mapping is right can only be asked of
 * a running server); starting a job feeds the file to the machine (the half no unit test spans:
 * {@code jobs} publishes a transition, {@code core} reads the file and hands it to the transport,
 * and the transport numbers, frames and writes it - asserted here as the G-code that reached
 * {@link MockPrinter}); and a feed at {@code BACKGROUND} is preempted and carries on where it was
 * (the one with teeth and the reason the issue exists, spanning the priority queue in
 * {@code :backend:terminal}, the send window in {@code :gcode} and the feeder in
 * {@code :backend:core}, needing a real file, a real window and a second source mid-stream - no
 * unit test anywhere can see it). There is no UI for any of this yet, so these endpoints are the
 * whole contract.
 *
 * <p>{@code FarmScheduler} is always running, assigning any {@code QUEUED} job the moment a machine
 * is {@code IDLE} and free - on every transition and a five-second sweep - so a test that queued
 * against a connected machine and posted {@code /assign} would race it into a 409. A job here is
 * therefore queued pinned to a machine the farm has never heard of, left queued with a stated
 * reason until moved by hand.
 *
 * <p>Jobs outlive the test that made them: {@code DELETE /api-jobs/{id}} cancels without removing
 * the row, and the whole module shares one database, so a job left {@code QUEUED} would be picked
 * up by the next test's printer. {@link #cancelWhatWasQueued()} is what stops that.
 */
class PrintJobIntegrationTest extends FarmIntegrationTest {

    /** What a cancelled or failed print leaves the machine in - {@code JobFeeder.coolDown}. */
    private static final List<String> COOL_DOWN =
            List.of("M107", "M104 S0", "M140 S0", "M83", "G1 E-2 F2400");

    private final List<UUID> queued = new ArrayList<>();

    /**
     * Not tidiness: a {@code QUEUED} job left behind is handed to the first {@code IDLE} machine
     * any later test registers, starting a print nobody asked for against a mock asserting an
     * exact command list.
     */
    @AfterEach
    void cancelWhatWasQueued() {
        for (var id : queued) {
            try {
                farm.delete().uri("/api-jobs/{id}", id).exchange();
            } catch (RuntimeException e) {
                // A job that already ended answers 409; a failed cleanup must not mask the failure that matters.
            }
        }
        queued.clear();
    }

    // ---------------------------------------------------------------- the queue, with no machine

    /**
     * Pinned to a nonexistent machine so it stays put while every field below is checked - a
     * scheduler quietly starting it would turn this into a race. {@code reason} on a queued job
     * says why it has no machine yet, a different sentence from why a job ended - see
     * {@code JobEntity.describe}.
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
     * 404, not 409: {@code JobOutcome.Refused} carries no state here since there is no job to have
     * one, which is what the controller keys on rather than guessing from an exception class - the
     * alternative is the 500 this shape exists to avoid.
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
     * The three fields are what a browser needs to recover: where it is, what was asked, a sentence
     * to show - the common cause is an ordinary race (somebody clicks Start as the scheduler does),
     * so the right answer is the truth, not a guess. Which moves are legal is not asserted here; one
     * illegal move is enough to pin the mapping.
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
     * All six verbs, because a browser's client parses each body and a missing job is the ordinary
     * consequence of two tabs open on the same queue.
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
     * Two different pages ask these - a farm-wide queue and a printer's own panel - asserted by
     * containment, not equality, since every job the suite has ever queued is still a row (see the
     * class note).
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
     * The baseline: the whole command log after the opening handshake is asserted, so a block
     * invented, duplicated, reordered or dropped anywhere between the file store and the socket
     * fails it - the failure this repository has shipped three times with every other test passing.
     */
    @Test
    void aStartedJobFeedsItsWholeFileToTheMachine() {
        theWholeFileReachesTheMachine(MockPrinter.marlin(), "fed");
    }

    /**
     * The acknowledgement dialect is the configuration axis a print job has: it decides how much
     * the host may hold outstanding, from {@code ok P15 B3}'s three blocks down to
     * {@code AdvancedOkRs(1, 1, null)}'s one, so "it prints to the end either way" is a real claim
     * over tens of thousands of blocks. The other profiles change nothing here and are not driven -
     * extruder count is not consulted by a feed, a card is not involved in host printing, and a
     * mute machine is {@code ConnectivityIntegrationTest}'s subject.
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
     * A job feeds at {@code BACKGROUND} and an interactive command is queued at {@code REGULAR},
     * so: {@code M105} takes the very next slot rather than waiting behind the file (the difference
     * between answering now and in six hours on a real print); the job is not restarted (no block
     * twice); and it is not dropped (no block missing) - the queue hands its remaining demand back
     * to the source it took it from. All three fall out of one assertion: remove {@code M105} from
     * what the machine received and what is left must be the file, exactly, in order.
     *
     * <p>{@link MockPrinter#holdRepliesFrom(String)} stops the machine answering so there is
     * something to preempt (an instant-answering mock finishes before the next test line runs); the
     * send window then holds the conversation at a few blocks, giving a sample that cannot move. The
     * ordering is not a race either: {@code PrinterReactor.handle} applies a command on the
     * aggregate's own executor and completes its {@code Mono} afterwards, so a 200 from
     * {@code /report-temp} means the {@code M105} is already queued before the job can be given
     * another unit of demand.
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
     * {@code JobFeeder} takes one exact reading at the moment it stops (the timer's checkpoints are
     * up to a second stale) and a resume seeks to it - a number even slightly behind means the
     * machine extrudes over what it has already printed, observed on a live run before the reading
     * was made exact. So: pause, and the job's number must be exactly the blocks that reached the
     * machine; then resume, and the file that comes out has to be the file, same assertion as the
     * preemption test.
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
     * The cool-down is queued at {@code REGULAR} rather than appended, so it does not wait behind
     * the rest of a six-hour print - the five blocks go in front, and what is left of the file
     * never goes. It does not home, park or disable the steppers either (homing drags the nozzle
     * across the part, dropping steppers drops Z with no brake), both recorded as debt in
     * {@code JobFeeder.coolDown} rather than guessed at; a change doing either should come through
     * here.
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
     * #60: a wrong outcome, not a wrong number. The firmware rejects {@code G21}, {@code G90} and
     * {@code G28} because its line counter is where a previous host session left it; the host
     * cannot answer a {@code Resend:} for a block it never framed, so
     * {@code CommandProcessor.resynchronise} renumbers and drops what it was holding, and the rest
     * of the file prints perfectly - before this fix the job reported {@code COMPLETED} with its
     * {@code G28} silently skipped.
     *
     * <p>No test caught it because a fresh {@link MockPrinter} starts at line 0 and a fresh host
     * numbers from 1, agreeing by construction; {@link MockPrinter#checkLineNumbersFrom(String, int)}
     * is the fixture that lets a machine outlive a host session, every reply line measured. The
     * drift is armed mid-file rather than by reconnecting because a reconnect's opening
     * {@code M110 N0} already repairs the sequence - measured on the wire by
     * {@code ResendOnReconnectProbe}:
     *
     * <pre>
     *   -&gt; N1 M110 N0*124
     *   &lt;- ok N1 P15 B3
     *   -&gt; N1 G21*27
     * </pre>
     *
     * What the opening cannot prevent is the two ends disagreeing <em>after</em> it - a lost
     * {@code ok}, a buffered half-line, an {@code Error:} on a line the window has passed - and
     * every one reaches {@code resynchronise} the same way, so one counter pushed before the file's
     * first block stands for all of them; what goes over the wire below is exactly what the
     * simulator produced. The three assertions are three different claims: the blocks really were
     * lost, the job says so, and it says why in words an operator can act on.
     *
     * <p>The machine is also stopped mid-file because without that gate this test is a coin flip,
     * measured as one: four passes in nine runs. The abandonment crosses two
     * {@code publishOn(executor)} hops onto the aggregate's thread ({@code PrinterReactor.gEvent}'s
     * and {@code updates()}'), while the feed's completion arrives independently on the netty
     * thread; against a mock that answers instantly, an eighteen-block file finishes in about ten
     * milliseconds, so the two race for whether the job reports {@code FAILED} or {@code COMPLETED}.
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
     * Captured before #54, when that executor was the farm-wide {@code Schedulers.single()} (hence
     * {@code single-1}); the same two hops now land on this machine's own {@code p3d-printer-<id>}
     * thread, which removes the farm's queueing from the gap and leaves the race itself exactly as
     * measured - two paths, not two speeds. That race is real but at fixture scale and not what
     * this test is for: on a real print the blocks are abandoned thousands of blocks before the
     * end, and the window to swap is the last few milliseconds. Recorded as known debt (closing it
     * needs the completion path to consult the fact synchronously rather than race it, which
     * touches the same code as #62 and belongs with it). So
     * {@link MockPrinter#holdRepliesFrom(String)} stops the machine mid-file (§4.4's tool, shared
     * with the preemption and pause tests): the file cannot run out, so the only terminal state
     * reachable is the one the abandonment puts it in, and a regression leaves the job
     * {@code RUNNING}.
     */
    @Test
    void aJobWhoseBlocksWereAbandonedFailsRatherThanReportingCompleted() {
        var printer = machine(MockPrinter.marlin());
        var id = register("abandoned blocks", printer);
        var file = GcodeFixture.unsliced("abandoned").named("abandoned.gcode").withBodyLines(12);
        var blocks = file.blocks();

        // From the file's first block on, this machine's counter stands at 14 - where a machine
        // that outlived a host session stands. The opening handshake (M110 N0, M115, M155 S1) is
        // answered normally, since those are not blocks out of a file.
        printer.checkLineNumbersFrom(blocks.getFirst(), 14);
        // Stops answering well after the resynchronisation, so the file cannot finish before the
        // abandonment is acted on - see the note above.
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
     * The control for the test above: a resend the host <em>can</em> answer costs the job nothing.
     * Without this, "fail the job when a {@code Resend:} arrives" would pass the test above and be
     * badly wrong - a corrupted block is ordinary traffic, the window holds every block until the
     * {@code ok} that accepts it, and replaying one is the mechanism spec 8.5 exists for. Measured
     * in the same probe run as the rejection, as the control:
     *
     * <pre>
     *   &gt;&gt; N2 G90*19  &lt;&lt; Error:checksum mismatch, Last Line: 1
     *                 &lt;&lt; Resend: 2
     *                 &lt;&lt; ok
     *   &gt;&gt; N2 G90*18  &lt;&lt; ok N2 P15 B3
     * </pre>
     *
     * The push is the firmware's half of that, to a session whose window is holding the block asked
     * for - the same shape as
     * {@code ConnectivityIntegrationTest.aStrayAcknowledgementDoesNotShiftTheSendWindow}, with a job
     * behind it.
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
     * The order keeps the scheduler out of the way and is the honest order anyway: pinned while
     * offline so no pass can take it, assigned by hand (out of {@code queued()} for good), then
     * dialled and started - every step the endpoint a browser would call.
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
