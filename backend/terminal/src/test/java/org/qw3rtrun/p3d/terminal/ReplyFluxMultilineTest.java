package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.event.EndstopReport;
import org.qw3rtrun.p3d.g.marlin.event.EndstopStateRs;
import org.qw3rtrun.p3d.g.marlin.event.EepromReport;
import org.qw3rtrun.p3d.g.marlin.event.SettingsReport;
import org.qw3rtrun.p3d.g.marlin.event.SettingsSection;
import org.qw3rtrun.p3d.g.marlin.event.SettingsValue;
import org.qw3rtrun.p3d.g.protocol.AdvancedOkRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * What {@link HostTerminal.ReplyFlux} emits for an answer that spans several lines: <strong>one
 * value per read, never one per line</strong>, with the {@code ok} that ended the burst still
 * arriving behind it.
 *
 * <p>A {@link StepVerifier} and not a collected list, because the whole claim is about <em>order</em>
 * and <em>count</em>: a report must be emitted before the {@code ok} it was flushed by, and the
 * {@code ok} must survive, since downstream it is the send window's credit (see
 * {@code HostTerminalTest.anOkThatEndsAnEndstopReportStillCreditsTheSendWindow} for the other end of
 * that). The pipeline under test is the real one - {@code ReplyFlux} over a {@code Flux<String>} of
 * wire lines - so framing, decoding and accumulation are all exercised, and no reply type is
 * constructed by hand except as an expectation.
 *
 * <p>The M119 fixture is the capture, verbatim: {@code MultilineInterleaveProbe} against MarlinSim
 * on {@code localhost:8099}, 2026-11-19, with {@code M155 S1} ticking once a second throughout -
 * the four report lines and the {@code ok P15 B3} all landed at 22ms, the surrounding temperature
 * ticks at 0ms and 691ms. Nothing lands inside the answer, which is what lets the accumulator be
 * scoped to "members of this family" rather than "everything until the ok".
 */
class ReplyFluxMultilineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** The real inbound pipeline over the given wire lines, each terminated as the socket delivers
     * it - binary mode off, traffic observer ignored, since neither is what these tests are about. */
    private static Flux<GRs<?>> replies(String... lines) {
        return Flux.from(new HostTerminal.ReplyFlux(
                Flux.fromArray(lines).map(line -> line + "\n"), () -> false, line -> {
        }));
    }

    private static EndstopStateRs open(String name) {
        return new EndstopStateRs(name, false);
    }

    /** The captured M119 answer: five lines in, one report plus its {@code ok} out. */
    @Test
    void anEndstopReportArrivesAsOneReplyFollowedByItsOk() {
        StepVerifier.create(replies(
                        "Reporting endstop status",
                        "x_min: open",
                        "y_min: open",
                        "z_min: open",
                        "ok P15 B3"))
                .assertNext(reply -> assertEquals(
                        List.of(open("x_min"), open("y_min"), open("z_min")),
                        assertInstanceOf(EndstopReport.class, reply).getStates(),
                        "all three states, in the order the firmware reported them"))
                .assertNext(reply -> assertInstanceOf(AdvancedOkRs.class, reply))
                .expectComplete()
                .verify(TIMEOUT);
    }

    /** The same for {@code M503}'s dump: a section, a value, and the {@code ok}. The {@code echo:}
     * shape - {@code ReplyFluxFramingTest} is where the unprefixed {@code M503 S0} shape (no
     * sections, leading indent as the only guard) is pinned, since issue #149 is what made that
     * shape survive framing at all. */
    @Test
    void aSettingsDumpArrivesAsOneReplyFollowedByItsOk() {
        StepVerifier.create(replies(
                        "echo:; Steps per unit:",
                        "echo:  M92 X80.00 Y80.00 Z400.00 E500.00",
                        "ok P15 B3"))
                .assertNext(reply -> assertEquals(
                        List.of(new SettingsSection("Steps per unit"),
                                new SettingsValue("M92", "M92 X80.00 Y80.00 Z400.00 E500.00")),
                        assertInstanceOf(SettingsReport.class, reply).getEntries()))
                .assertNext(reply -> assertInstanceOf(AdvancedOkRs.class, reply))
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * A single-setting read-back ({@code M92} alone) is one value line and no section, and it still
     * becomes a {@link SettingsReport} - deliberate uniformity: every settings answer is one report
     * whatever its length, so a consumer has one shape to handle and not two. Pinned because
     * "simplifying" a one-entry report back to a bare {@link SettingsValue} on the bus would be a
     * breaking change to that consumer.
     */
    @Test
    void aLoneSettingsLineIsStillAWholeReport() {
        StepVerifier.create(replies(
                        "echo:  M92 X81.00 Y80.00 Z400.00 E500.00",
                        "ok P15 B3"))
                .assertNext(reply -> assertEquals(
                        List.of(new SettingsValue("M92", "M92 X81.00 Y80.00 Z400.00 E500.00")),
                        assertInstanceOf(SettingsReport.class, reply).getEntries(),
                        "one entry, and still a report"))
                .assertNext(reply -> assertInstanceOf(AdvancedOkRs.class, reply))
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * {@code M504}'s answer is a {@code SettingsRs} by family but a whole answer by itself, so it
     * passes through as itself rather than being wrapped in a one-entry {@link SettingsReport}. The
     * reader is what decides that - {@code SettingsReportReader.offer} declines it - and this is the
     * test that holds the decision on this side of the boundary.
     */
    @Test
    void anEepromReportIsItsOwnReplyAndNotWrappedInASettingsReport() {
        StepVerifier.create(replies("echo:EEPROM OK", "ok P15 B3"))
                .assertNext(reply -> assertEquals("OK",
                        assertInstanceOf(EepromReport.class, reply).getMessage()))
                .assertNext(reply -> assertInstanceOf(AdvancedOkRs.class, reply))
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * Two reads back to back, as the send window really emits them for two commands queued one after
     * the other: each burst flushes at its own boundary and neither picks up a line of the other.
     * Marlin executes its command queue strictly in order, so whole answers cannot interleave at the
     * protocol level - only auto-reports and busy notices can land <em>between</em> them, which the
     * 2026-11-19 capture shows landing outside both.
     */
    @Test
    void twoReadsBackToBackDoNotBleedIntoEachOther() {
        StepVerifier.create(replies(
                        "Reporting endstop status",
                        "x_min: open",
                        "ok P15 B3",
                        "echo:; Steps per unit:",
                        "echo:  M92 X80.00",
                        "ok P15 B3"))
                .assertNext(reply -> assertEquals(List.of(open("x_min")),
                        assertInstanceOf(EndstopReport.class, reply).getStates()))
                .assertNext(reply -> assertInstanceOf(AdvancedOkRs.class, reply))
                .assertNext(reply -> assertEquals(
                        List.of(new SettingsSection("Steps per unit"),
                                new SettingsValue("M92", "M92 X80.00")),
                        assertInstanceOf(SettingsReport.class, reply).getEntries()))
                .assertNext(reply -> assertInstanceOf(AdvancedOkRs.class, reply))
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * A burst the connection dies in the middle of is <strong>lost</strong>, not emitted short: no
     * non-member line ever arrives, so nothing flushes. The same strict reading {@code SdFileListReader}
     * takes for {@code M20} - a partial answer reported as the whole one is worse than no answer -
     * and what this transport already does to every other exchange in flight on a dead socket. Pinned
     * so the loss is a decision on the record rather than a surprise.
     */
    @Test
    void anEndstopReportTheConnectionDiedInsideIsLostRatherThanReportedShort() {
        StepVerifier.create(replies("Reporting endstop status", "x_min: open"))
                .expectComplete()
                .verify(TIMEOUT);
    }
}
