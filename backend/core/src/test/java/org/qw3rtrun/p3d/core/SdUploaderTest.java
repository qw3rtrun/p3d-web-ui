package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two decisions {@link SdUploader} makes that are not plumbing: what the transfer looks like,
 * and what the file ends up being called - both asserted against the real code,
 * {@code transferOf} taking its pieces as arguments so the shape can be driven without a printer.
 */
class SdUploaderTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    // M20 lists only .gcode, .gco and .g; a file written as benchy is on the card and printable by
    // exact name, but appears in no listing - measured, not read: a probe once wrote an
    // extensionless file, got an empty listing, and the first conclusion was that the simulator was
    // broken.
    @ParameterizedTest
    @CsvSource({
            "benchy,          benchy.gco",
            "benchy.stl,      benchy.stl.gco",
            "a,               a.gco",
            "  spaced  ,      spaced.gco",
    })
    void aNameWithoutAPrintableExtensionGetsOne(String requested, String expected) {
        assertEquals(expected, SdUploader.cardNameFor(requested));
    }

    /** One that already has a printable extension is left exactly as it is, case and all. */
    @ParameterizedTest
    @CsvSource({
            "benchy.gco,      benchy.gco",
            "benchy.gcode,    benchy.gcode",
            "benchy.g,        benchy.g",
            "BENCHY.GCO,      BENCHY.GCO",
            "Benchy.GcOdE,    Benchy.GcOdE",
    })
    void aPrintableExtensionIsLeftAlone(String requested, String expected) {
        assertEquals(expected, SdUploader.cardNameFor(requested));
    }

    // the extension test is on the tail, not "contains a dot": notes.gco.txt ends in .txt and needs
    // the extension, which matching the dot anywhere would have missed.
    @Test
    void aPrintableExtensionInTheMiddleDoesNotCount() {
        assertEquals("notes.gco.txt.gco", SdUploader.cardNameFor("notes.gco.txt"));
    }

    // ------------------------------------------------- the shape of a transfer

    /** Open the file, write every block of it, close it. */
    @Test
    void aTransferIsTheOpenTheFileAndTheClose() {
        var stop = Sinks.<Void>empty();

        StepVerifier.create(SdUploader.transferOf(
                        "benchy.gco", Flux.just("G28", "G1 X1", "G1 X2"), stop.asMono()))
                .expectNext("M28 benchy.gco")
                .expectNext("G28", "G1 X1", "G1 X2")
                .expectNext("M29")
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * A stop still closes the file - the one claim here about correctness, not tidiness. Cancelling
     * outright instead would leave the file open: the exclusive hold would end, and the very next
     * command given to the machine would be written into the G-code - the corruption this mechanism
     * exists to prevent, caused by the button meant to stop it.
     */
    @Test
    void aStoppedTransferStillSendsTheClose() {
        var stop = Sinks.<Void>empty();
        var content = TestPublisher.<String>create();

        StepVerifier.create(SdUploader.transferOf("benchy.gco", content.flux(), stop.asMono()))
                .expectNext("M28 benchy.gco")
                .then(() -> content.next("G28"))
                .expectNext("G28")
                .then(() -> content.next("G1 X1"))
                .expectNext("G1 X1")

                // the operator presses Cancel - the source is not torn out, it is told to stop.
                .then(() -> stop.tryEmitEmpty())

                .expectNext("M29")
                .expectComplete()
                .verify(TIMEOUT);
    }

    // a narrow but real case: the transfer is queued, the operator changes their mind, and the file
    // is read a moment later - an M28 with no M29 behind it is the worst outcome, so the close is
    // unconditional.
    @Test
    void aTransferStoppedBeforeItStartedStillCloses() {
        var stop = Sinks.<Void>empty();
        stop.tryEmitEmpty();

        StepVerifier.create(SdUploader.transferOf("benchy.gco", Flux.never(), stop.asMono()))
                .expectNext("M28 benchy.gco", "M29")
                .expectComplete()
                .verify(TIMEOUT);
    }

    /** An empty file is an open and a close, and the firmware is happy with that. */
    @Test
    void anEmptyFileIsStillAFile() {
        StepVerifier.create(SdUploader.transferOf("empty.gco", Flux.empty(), Sinks.<Void>empty().asMono()))
                .expectNext("M28 empty.gco", "M29")
                .expectComplete()
                .verify(TIMEOUT);
    }
}
