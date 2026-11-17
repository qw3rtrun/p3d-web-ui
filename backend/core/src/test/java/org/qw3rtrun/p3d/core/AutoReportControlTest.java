package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which auto-reports a session turned on, and the blocks that undo exactly those.
 *
 * <p>Asserted as the encoded G-code rather than the command types, for the same reason
 * {@code PrinterStateStorageTest} does: {@code M155 S0} is the contract and
 * {@code TemperatureAutoReport[seconds=0]} is one refactor away from it.
 */
class AutoReportControlTest {

    private final AutoReportControl autoReports = new AutoReportControl();

    /** The teardown as it would reach the wire. */
    private List<String> teardown() {
        return autoReports.teardown().stream().map(AutoReportControlTest::encode).toList();
    }

    private static String encode(GRq<?> command) {
        return GEncoder.INSTANCE.encode(command.encode());
    }

    @Test
    void aSessionThatStartedNothingHasNothingToUndo() {
        assertEquals(List.of(), teardown());
    }

    @Test
    void temperatureAloneIsTheOrdinaryCase() {
        autoReports.temperatureStarted();

        assertEquals(List.of("M155 S0"), teardown());
    }

    @Test
    void everyReportStartedIsUndoneAndNoOthersAre() {
        autoReports.temperatureStarted();
        autoReports.positionStarted();
        autoReports.sdStatusStarted();

        assertEquals(List.of("M155 S0", "M154 S0", "M27 S0"), teardown());
    }

    /**
     * A report that was never started has no off-switch in the list - the teardown undoes what this
     * session did rather than sending the off-switch of everything Marlin has, which on a machine
     * that never reported the capability is a block for nothing at the one moment the farm is
     * working to a budget.
     */
    @Test
    void aReportThatWasNeverStartedIsNotTurnedOff() {
        autoReports.temperatureStarted();
        autoReports.sdStatusStarted();

        assertEquals(List.of("M155 S0", "M27 S0"), teardown());
    }

    /**
     * The order is the order they were started in, not a fixed one: the teardown is a log being
     * replayed backwards in intent, and a machine that answered its capabilities in a different
     * order started them in a different order.
     */
    @Test
    void theOrderIsTheOrderTheyWereStartedIn() {
        autoReports.sdStatusStarted();
        autoReports.positionStarted();
        autoReports.temperatureStarted();

        assertEquals(List.of("M27 S0", "M154 S0", "M155 S0"), teardown());
    }

    /** Starting the same report twice is one subscription and therefore one off-switch. */
    @Test
    void startingOneTwiceStillUndoesItOnce() {
        autoReports.temperatureStarted();
        autoReports.temperatureStarted();

        assertEquals(List.of("M155 S0"), teardown());
    }

    /**
     * A new session owns nothing the last one started - it may be a reflashed machine, or a
     * different machine at the same address, exactly as {@code SdCardControl.sessionStarted()}
     * argues. The off-switches of a session whose socket has gone are not this one's to send.
     */
    @Test
    void aNewSessionOwnsNothingTheLastOneStarted() {
        autoReports.temperatureStarted();
        autoReports.positionStarted();

        autoReports.sessionStarted();

        assertEquals(List.of(), teardown());
    }

    /**
     * {@code teardown()} reads rather than clears, so the idempotency of a second
     * {@code PrinterState.quiesce()} is the aggregate's to arrange (it calls
     * {@link AutoReportControl#sessionStarted()} afterwards) and not something this class decides
     * on a caller's behalf - a caller that wants to know what is running must be able to ask twice.
     */
    @Test
    void readingTheTeardownDoesNotConsumeIt() {
        autoReports.temperatureStarted();

        assertEquals(List.of("M155 S0"), teardown());
        assertEquals(List.of("M155 S0"), teardown());
    }
}
