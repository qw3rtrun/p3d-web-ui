package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.event.SettingsReport;
import org.qw3rtrun.p3d.g.marlin.event.SettingsValue;
import org.qw3rtrun.p3d.g.protocol.AdvancedOkRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link HostTerminal.ReplyFlux}'s framing stage - issue #149. Three things pinned here that the
 * {@code flatMap}-over-characters shape it replaced either broke or only held by accident:
 *
 * <ul>
 * <li>the leading two-space indent {@link SettingsValue}'s unprefixed shape decodes on survives
 * framing now that the assembled line is {@code stripTrailing()}'d rather than {@code trim()}'d -
 * {@link #anM503S0DumpDecodesToOneSettingsReport} and {@link #aBareM92ReadBackDecodesToASettingsReport};</li>
 * <li>a reply split across two reads is rejoined, and a chunk holding several replies yields
 * several - {@link #aLineSplitAcrossTwoReadsIsRejoined} and
 * {@link #aChunkHoldingSeveralRepliesYieldsThemAllInOrder};</li>
 * <li>order survives at volume and under arbitrary read boundaries - the regression risk once the
 * {@code flatMap}'s two accidents (one netty thread, unbounded downstream demand) are no longer
 * what framing depends on - {@link #orderSurvivesManyRepliesCutAtArbitraryChunkBoundaries}.</li>
 * </ul>
 *
 * <p>The M503 S0 fixture is {@code SettingsRsTest.TheTwoShapes}'s 13 value lines, verbatim and with
 * no {@code echo:} prefix - exactly what MarlinSim answers {@code M503 S0} with
 * ({@code SettingsProbe}'s captured table) and what a bare single-setting query like {@code M92}
 * answers in too.
 */
class ReplyFluxFramingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** The 13 unprefixed, two-space-indented value lines of {@code M503 S0}, captured off MarlinSim
     * - the same fixture {@code SettingsRsTest.TheTwoShapes} uses for the decoder on its own. */
    private static final List<String> M503_S0_LINES = List.of(
            "  G21 ; (mm)",
            "  M104 S0 B250 F0.00",
            "  M149 C ; Units in Celsius",
            "  M200 S0 D1.75",
            "  M92 X80.00 Y80.00 Z400.00 E500.00",
            "  M203 X300.00 Y300.00 Z5.00 E25.00",
            "  M201 X3000.00 Y3000.00 Z100.00 E10000.00",
            "  M204 P3000.00 R3000.00 T3000.00",
            "  M205 B20000.00 S0.00 T0.00 J0.01",
            "  M206 X0.00 Y0.00 Z0.00",
            "  M145 S0 H180.00 B70.00 F0",
            "  M145 S1 H240.00 B110.00 F0",
            "  M301 P22.20 I1.08 D114.00"
    );

    /** The real framing/decode/fold pipeline over the given wire chunks exactly as given - nothing
     * appended, so a test can cut a line in half the way TCP does. Binary mode off throughout. */
    private static Flux<GRs<?>> overChunks(String... chunks) {
        return new HostTerminal.ReplyFlux(Flux.fromArray(chunks), () -> false, line -> {
        });
    }

    private static Flux<GRs<?>> overLines(String... lines) {
        var chunks = new ArrayList<String>(lines.length);
        for (var line : lines) chunks.add(line + "\n");
        return overChunks(chunks.toArray(String[]::new));
    }

    /**
     * {@code M503 S0}'s whole answer - no {@code echo:} prefix, no section headers, the leading
     * indent as the only guard - still folds into one {@link SettingsReport}. Before the fix,
     * {@code ReplyFlux}'s framing {@code .trim()}'d every assembled line and the indent these 13
     * lines depend on to decode at all did not survive that, so the dump was silently dropped
     * (logged {@code <- (unread)}) and nothing reached the bus.
     */
    @Test
    void anM503S0DumpDecodesToOneSettingsReport() {
        var lines = new ArrayList<>(M503_S0_LINES);
        lines.add("ok P15 B3");
        StepVerifier.create(overLines(lines.toArray(String[]::new)))
                .assertNext(reply -> assertEquals(13,
                        assertInstanceOf(SettingsReport.class, reply).getEntries().size(),
                        "all 13 unprefixed value lines, folded into one report"))
                .assertNext(reply -> assertInstanceOf(AdvancedOkRs.class, reply))
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * A bare {@code M92} read-back shares the exact same unprefixed, indented shape as one line of
     * {@code M503 S0} - and the fix has to cover it too, since it is the same decode guard and the
     * same framing it has to survive.
     */
    @Test
    void aBareM92ReadBackDecodesToASettingsReport() {
        StepVerifier.create(overLines("  M92 X81.00 Y80.00 Z400.00 E500.00", "ok P15 B3"))
                .assertNext(reply -> assertEquals(
                        List.of(new SettingsValue("M92", "M92 X81.00 Y80.00 Z400.00 E500.00")),
                        assertInstanceOf(SettingsReport.class, reply).getEntries()))
                .assertNext(reply -> assertInstanceOf(AdvancedOkRs.class, reply))
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * The two halves of one line arriving in two separate {@code onNext}s - exactly what a read that
     * lands mid-line looks like over a real socket - still frame as one line.
     */
    @Test
    void aLineSplitAcrossTwoReadsIsRejoined() {
        StepVerifier.create(overChunks("  M92 X81.00 Y80.00", " Z400.00 E500.00\nok P15 B3\n"))
                .assertNext(reply -> assertEquals(
                        List.of(new SettingsValue("M92", "M92 X81.00 Y80.00 Z400.00 E500.00")),
                        assertInstanceOf(SettingsReport.class, reply).getEntries()))
                .assertNext(reply -> assertInstanceOf(AdvancedOkRs.class, reply))
                .expectComplete()
                .verify(TIMEOUT);
    }

    /** One chunk carrying three whole lines yields three replies, in the order they were written -
     * what a Marlin answer that outruns one TCP read in the other direction looks like. */
    @Test
    void aChunkHoldingSeveralRepliesYieldsThemAllInOrder() {
        StepVerifier.create(overChunks("  M92 X80.00\n  M203 X300.00\nok P15 B3\n"))
                .assertNext(reply -> assertEquals(
                        List.of(new SettingsValue("M92", "M92 X80.00"),
                                new SettingsValue("M203", "M203 X300.00")),
                        assertInstanceOf(SettingsReport.class, reply).getEntries()))
                .assertNext(reply -> assertInstanceOf(AdvancedOkRs.class, reply))
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * 300 {@code ok} lines, one whole stream of text cut into chunks of every length from 1 to 40
     * characters rather than at line boundaries - the shape a TCP read actually takes, and nowhere
     * near the convenient "one line, one chunk" case the tests above use. Framing used to depend on
     * two accidents of the pipeline around it to keep this in order (one netty thread, and nothing
     * bounding demand below unbounded - see {@code HostTerminal.ReplyFlux.frame}'s javadoc); this
     * stage has no inner publisher at all now, so there is nothing left for either accident to have
     * been protecting against, and this is the test that would notice if there were.
     */
    @Test
    void orderSurvivesManyRepliesCutAtArbitraryChunkBoundaries() {
        var whole = IntStream.rangeClosed(1, 300)
                .mapToObj(i -> "ok N" + i + " P15 B3\n")
                .collect(joining());

        var chunks = new ArrayList<String>();
        var at = 0;
        var step = 1;
        while (at < whole.length()) {
            var end = Math.min(at + step, whole.length());
            chunks.add(whole.substring(at, end));
            at = end;
            step = step >= 40 ? 1 : step + 1;
        }

        StepVerifier.create(overChunks(chunks.toArray(String[]::new)))
                .recordWith(ArrayList::new)
                .expectNextCount(300)
                .consumeRecordedWith(replies -> assertEquals(
                        IntStream.rangeClosed(1, 300).boxed().toList(),
                        replies.stream().map(reply -> ((AdvancedOkRs) reply).getLineNumber()).toList(),
                        "every one of 300 replies, framed whole and in order"))
                .expectComplete()
                .verify(TIMEOUT);
    }
}
