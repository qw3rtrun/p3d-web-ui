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
 * and what the file ends up being called.
 *
 * <p>Both are asserted against the real code rather than a copy of it - {@code transferOf} is the
 * builder the uploader actually uses, taking its pieces as arguments so that the shape can be
 * driven without a printer on the end.
 */
class SdUploaderTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * A name without a printable extension gets one, because the alternative is an invisible file.
     *
     * <p>{@code M20} lists only {@code .gcode}, {@code .gco} and {@code .g}. A file written as
     * {@code benchy} is on the card and printable by exact name, and appears in no listing - so an
     * operator who typed that would have made a file they cannot find, let alone delete.
     *
     * <p>Measured rather than read: a probe wrote a file with no extension, got an empty listing
     * back, and the first conclusion drawn was that the simulator was broken.
     */
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

    /**
     * The extension test is on the tail and not on "contains a dot".
     *
     * <p>{@code notes.gco.txt} ends in {@code .txt}, so it is not printable and needs the
     * extension - matching on the dot anywhere would have let it through as listable when it is
     * not.
     */
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
     * <strong>A stop still closes the file</strong>, which is the one claim in this class that is
     * about correctness rather than tidiness.
     *
     * <p>The content ends where it was told to and {@code M29} goes out behind it, so the card
     * holds a short but <em>closed</em> file. Cancelling the transfer outright instead would leave
     * it <strong>open</strong>: the exclusive hold on the connection would end, and the very next
     * command the machine was given - a temperature poll, a jog, anything - would be written into
     * the G-code. That is the corruption the whole exclusive-source mechanism exists to prevent,
     * caused by the button meant to stop it.
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

                // The operator presses Cancel. The source is not torn out - it is told to stop.
                .then(() -> stop.tryEmitEmpty())

                .expectNext("M29")
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * A stop before a single block still opens and closes a file, rather than sending half a pair.
     *
     * <p>The reachable case is narrow but real: the transfer is queued, the operator changes their
     * mind, and the file is read off disk a moment later. An {@code M28} already on the wire with
     * no {@code M29} behind it is the worst outcome available, so the close is unconditional.
     */
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
