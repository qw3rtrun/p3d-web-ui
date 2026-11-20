package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.event.CapabilitiesReport;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
import org.qw3rtrun.p3d.g.protocol.AdvancedOkRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code M115}'s capability block, folded by {@link HostTerminal.ReplyFlux} - issue #144. The
 * pipeline is driven directly over a {@code Flux} of wire chunks, so what is asserted is what the
 * real framing, the real decoder and the real fold produce, with no socket and no send window in the
 * way.
 *
 * <p>Asserted here rather than only on {@link HostTerminal#messageFlux()} because the burst's
 * closing {@code ok} is <em>consumed</em> there as window credit, and the whole reason the fold is a
 * {@code concatMapIterable} and not a second {@code handle} stage is that the line ending a burst has
 * to emit twice: the report, and then itself, so that the {@code ok} still reaches the sender. This
 * is the only level at which both halves of that are visible.
 *
 * <p>The scope of a burst - every reply that is a {@code Cap:} line, ended by the first that is not -
 * is what {@code CapabilitiesInterleaveProbe} measured against MarlinSim on 2026-11-20: the header,
 * all 38 {@code Cap:} lines and the {@code ok} at one millisecond offset, with the {@code M155 S1}
 * ticks outside the burst on both sides.
 */
class ReplyFluxCapabilitiesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final String M115_FIRMWARE =
            "FIRMWARE_NAME:Marlin bugfix-2.1.x (Oct 29 2026 10:28:56) "
                    + "SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0 "
                    + "MACHINE_TYPE:MarlinSim KINEMATICS:Cartesian EXTRUDER_COUNT:1 "
                    + "UUID:00000000-0000-0000-0000-000000000000";

    /** The reply pipeline over wire chunks exactly as given - nothing appended, so a test can cut a
     * line in half across two reads the way TCP does. Binary mode off: {@code M115} is ASCII. */
    private static Flux<GRs<?>> overChunks(String... chunks) {
        return new HostTerminal.ReplyFlux(Flux.fromArray(chunks), () -> false, line -> {
        });
    }

    /** The same over whole lines, each terminated as the firmware terminates them. */
    private static Flux<GRs<?>> overLines(String... lines) {
        return overChunks(Stream.of(lines).map(line -> line + "\n").toArray(String[]::new));
    }

    private static CapabilitiesReport report(CapabilityRs... capabilities) {
        return new CapabilitiesReport(List.of(capabilities));
    }

    /**
     * The whole answer: header, the capability lines, then the {@code ok} that closes it. Two things
     * at once, and the second is the one a {@code handle} stage could not do - the {@code ok} is
     * emitted <em>after</em> the report it flushed, and it is emitted at all, which is what keeps it
     * window credit for the send window one layer up.
     */
    @Test
    void aCapabilityBurstArrivesAsOneReportAndThenTheOkThatEndedIt() {
        StepVerifier.create(overLines(M115_FIRMWARE,
                        "Cap:AUTOREPORT_TEMP:1",
                        "Cap:EEPROM:1",
                        "Cap:EMERGENCY_PARSER:0",
                        "ok P15 B3"))
                .expectNextMatches(FirmwareInfoRs.class::isInstance)
                .expectNext(report(new CapabilityRs("AUTOREPORT_TEMP", true),
                        new CapabilityRs("EEPROM", true),
                        new CapabilityRs("EMERGENCY_PARSER", false)))
                .expectNextMatches(AdvancedOkRs.class::isInstance)
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * A firmware older than Marlin 1.1.9 answers {@code M115} with the header alone, and the
     * degraded case issue #144 asks to preserve is that nothing is invented for it: no report, not an
     * empty one. Nothing was opened, so there is nothing to flush.
     */
    @Test
    void aFirmwareThatSendsNoCapabilityLinesProducesNoReportAtAll() {
        StepVerifier.create(overLines(M115_FIRMWARE, "ok P15 B3"))
                .expectNextMatches(FirmwareInfoRs.class::isInstance)
                .expectNextMatches(AdvancedOkRs.class::isInstance)
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * Two {@code M115} exchanges in one session, with a temperature tick between them: the first
     * burst is closed by its own {@code ok}, and the second starts from empty rather than continuing
     * the first. The reader's per-flush reset is a claim about state that survives between bursts, so
     * it is pinned through the real pipeline and not only in isolation - a reader that kept its list
     * would report the first session's capabilities again in the second report.
     */
    @Test
    void aSecondBurstStartsFreshRatherThanContinuingTheFirst() {
        StepVerifier.create(overLines(M115_FIRMWARE,
                        "Cap:EEPROM:1",
                        "ok P15 B3",
                        "T:25.00 /0.00 B:25.00 /0.00 @:0 B@:0",
                        M115_FIRMWARE,
                        "Cap:SDCARD:1",
                        "Cap:SD_WRITE:0",
                        "ok P15 B3"))
                .expectNextMatches(FirmwareInfoRs.class::isInstance)
                .expectNext(report(new CapabilityRs("EEPROM", true)))
                .expectNextMatches(AdvancedOkRs.class::isInstance)
                .expectNextMatches(TemperatureRs.class::isInstance)
                .expectNextMatches(FirmwareInfoRs.class::isInstance)
                .expectNext(report(new CapabilityRs("SDCARD", true),
                        new CapabilityRs("SD_WRITE", false)))
                .expectNextMatches(AdvancedOkRs.class::isInstance)
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * The socket dying inside an {@code M115} answer publishes nothing rather than a short report -
     * the fold only ever emits when a reply ends a burst, and a stream that ends instead has no such
     * reply. Deliberate, and the same answer an interrupted {@code SdFileList} gets: a capability
     * missing from a truncated list reads as one the firmware does not have, which is a worse answer
     * than no report.
     */
    @Test
    void aBurstTheStreamEndedInsidePublishesNothing() {
        StepVerifier.create(overLines(M115_FIRMWARE, "Cap:EEPROM:1", "Cap:SDCARD:1"))
                .expectNextMatches(FirmwareInfoRs.class::isInstance)
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * Framing is unchanged by the stage above it, in both directions the module's framing has to hold:
     * a line split across two reads is rejoined, and a single read holding many lines yields all of
     * them in order.
     *
     * <p>The second half is the one worth having. {@code concatMapIterable} is the first operator in
     * this pipeline with a prefetch ({@code XS_BUFFER_SIZE}, 32), so the chain is no longer driven by
     * {@code ReplaySubscriber}'s unbounded demand alone - and unbounded demand is one of the two
     * accidents that keep the {@code flatMap}-based framing from interleaving two chunks' characters.
     * 100 replies in one chunk is three times the prefetch, so a half-drained inner would show up
     * here as a line framed out of nonsense or out of order.
     */
    @Test
    void framingSurvivesTheFoldBothAcrossAReadAndWithinOne() {
        StepVerifier.create(overChunks("Cap:EEPR", "OM:1\nok P15 B3\n"))
                .expectNext(report(new CapabilityRs("EEPROM", true)))
                .expectNextMatches(AdvancedOkRs.class::isInstance)
                .expectComplete()
                .verify(TIMEOUT);

        String oneChunk = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> "ok N" + i + " P15 B3\n")
                .collect(joining());
        StepVerifier.create(overChunks(oneChunk))
                .recordWith(ArrayList::new)
                .expectNextCount(100)
                .consumeRecordedWith(replies -> assertEquals(
                        IntStream.rangeClosed(1, 100).boxed().toList(),
                        replies.stream().map(reply -> ((AdvancedOkRs) reply).getLineNumber()).toList(),
                        "every line of one read, framed in order"))
                .expectComplete()
                .verify(TIMEOUT);
    }
}
