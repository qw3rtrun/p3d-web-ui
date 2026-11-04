package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.BlocksAbandoned;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A subscriber that arrives late is told what the machine is, and not what it last said - #85.
 *
 * <p>{@code updates()} used to replay its latest message. That handed a page opened on a warm
 * printer a blank until the next auto-report, unless the latest message happened to be a
 * temperature, and handed a job's watcher an earlier job's {@code BlocksAbandoned} as if it were
 * its own. It now opens with the aggregate's snapshot.
 *
 * <p>{@code Schedulers.immediate()} for the executor, so emission and delivery happen on the test
 * thread in the order the code decides them.
 */
class PrinterReactorSnapshotTest {

    private final UUID id = UUID.randomUUID();

    private PrinterState printer;
    private PrinterReactor reactor;

    @BeforeEach
    void build() {
        printer = new PrinterState(id);
        reactor = new PrinterReactor(new TerminalManager(new ConnectionDetails("localhost", 1)),
                printer, event -> {
        }, ConnectionPolicy.standard(), Schedulers.immediate(), Schedulers.parallel());
    }

    @AfterEach
    void letGo() {
        reactor.stop();
    }

    @Test
    void aLateSubscriberIsHandedTheLastTemperatureOnceAndThenWhatHappensNext() {
        printer.onEvent(temperature("210.00"));

        StepVerifier.create(reactor.updates().map(EventMessage::event))
                .assertNext(first -> assertInstanceOf(ConnectionStateChanged.class, first,
                        "the connection state leads, so a subscriber can wait for it"))
                .expectNext(temperature("210.00"))
                .then(() -> printer.onEvent(temperature("211.00")))
                .expectNext(temperature("211.00"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    /** The other half of the replay bug: something that happened is not news to whoever comes next. */
    @Test
    void whatHappenedBeforeASubscriberArrivedIsNotHandedToIt() {
        printer.onEvent(new BlocksAbandoned(15, 3));

        StepVerifier.create(reactor.updates().map(EventMessage::event).take(Duration.ofMillis(200)))
                .assertNext(only -> assertInstanceOf(ConnectionStateChanged.class, only))
                .verifyComplete();
    }

    /** A machine that has not reported a temperature is a connection state and nothing else - not a 500. */
    @Test
    void aMachineThatHasReportedNothingIsItsConnectionState() {
        StepVerifier.create(reactor.updates().take(Duration.ofMillis(200)))
                .assertNext(only -> assertEquals(id, only.id()))
                .verifyComplete();
        StepVerifier.create(reactor.state()).verifyComplete();
    }

    private static BareTemperatureRs temperature(String hotend) {
        return new BareTemperatureRs(new HeaterReading(new BigDecimal(hotend), new BigDecimal("210.00"), 127),
                null, null, null, null, null, null, Map.of());
    }
}
