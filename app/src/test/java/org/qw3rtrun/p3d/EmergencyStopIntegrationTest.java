package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #147 item 3's other half: {@code M112}, reaching the wire whether or not the printer is
 * still talking.
 *
 * <h2>Not a priority - a lane of its own</h2>
 * The first attempt queued {@code M112} through {@code PublisherQueue} at {@code Priority.EMERGENCY}
 * - plumbing that already existed and was already tested at {@code :backend:terminal} level - and it
 * does not work for the one case this feature is for: {@code HostTerminal.CommandProcessor} only asks
 * {@code PublisherQueue} for more when its own {@code canProcess()} says there is room in the
 * printer's own buffer, and a firmware that has stopped acknowledging holds that at zero forever, so
 * a queued {@code EMERGENCY} item is never even pulled. {@code HostTerminal.emergencyStop()} is the
 * fix: it writes {@code M112} unframed, past {@code canProcess()} entirely, through its own emergency
 * lane - proved at the terminal layer by {@code HostTerminalEmergencyTest} against a send window built
 * to stay shut. What is left for this class to prove is the same claim through the whole stack,
 * against a socket and a real HTTP request, which no unit test can stand in for.
 *
 * <h2>The control run</h2>
 * {@code SdCardIntegrationTest.theMachineTakesNothingElseWhileAFileIsGoingOntoTheCard} already
 * proves the opposite case: an ordinary {@code REGULAR} command (an {@code M105}) waits behind an
 * upload holding the wire at {@code REALTIME}. {@link #emergencyStopReachesTheWireWhileAnUploadHoldsIt}
 * is that same setup with the one write that is not supposed to wait - "it worked" on its own would
 * prove nothing about pre-emption that did not happen to fire; the paired test that does not pre-empt
 * is what makes the one that does mean something.
 */
class EmergencyStopIntegrationTest extends FarmIntegrationTest {

    /** The ordinary case: connected, idle, nothing in the way. */
    @Test
    void emergencyStopReachesAnIdleMachine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("idle, stopped anyway", printer);
        connectAndSettle(id, printer);

        command(id, "/emergency-stop").expectStatus().isOk();

        await("M112 to reach the machine", () -> printer.sent("M112"));
    }

    /**
     * An upload holds the wire exclusively at {@code REALTIME} (§6.3) - everything this farm sends
     * at {@code REGULAR} queues behind it until the transfer ends or is released, and so does the
     * send window itself once the printer stops acknowledging, which this test also arranges for
     * ({@code holdRepliesFrom}). {@code M112} must reach the wire regardless: it goes out through
     * {@code HostTerminal}'s own emergency lane, which answers to neither.
     */
    @Test
    void emergencyStopReachesTheWireWhileAnUploadHoldsIt() {
        var printer = machine(MockPrinter.marlin());
        var id = register("emergency during upload", printer);
        var file = GcodeFixture.unsliced("estop-during-upload").named("estop.gcode").withBodyLines(24);
        var blocks = file.blocks();
        printer.holdRepliesFrom(blocks.get(5));
        connectAndSettle(id, printer);

        var stored = idOf(upload(file));
        command(id, "/sd/upload", "{\"fileId\":\"" + stored + "\",\"name\":\"ESTOP.GCO\","
                + "\"binary\":false,\"trim\":false}").expectStatus().isOk();
        stalled(printer, blocks.get(5));
        printer.forget();

        command(id, "/emergency-stop").expectStatus().isOk();

        await(() -> "M112 to reach the machine while the upload is still held. Received "
                        + printer.commands(),
                () -> printer.sent("M112"));
        assertFalse(printer.sent("M29"),
                "the transfer must still be held open - M112 arriving because the transfer "
                        + "finished on its own would prove nothing: " + printer.commands());

        printer.answerFreely();
    }

    /** A machine the farm does not hold is {@code ABSENT}, the same as every other control route. */
    @Test
    void emergencyStopOnAnUnknownMachineIsRefused() {
        var response = command(java.util.UUID.randomUUID(), "/emergency-stop");

        var body = json(response.expectStatus().isNotFound());
        assertTrue("ABSENT".equals(body.get("refusal")), "the wrong kind of refusal: " + body);
        var reason = (String) body.get("reason");
        assertNotNull(reason, "a refusal with no sentence in it is one the browser cannot show");
        assertFalse(reason.isBlank());
    }
}
