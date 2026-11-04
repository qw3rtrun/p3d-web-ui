package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.event.SdCardOk;
import org.qw3rtrun.p3d.g.marlin.event.SdCurrentFile;
import org.qw3rtrun.p3d.g.marlin.event.SdDonePrinting;
import org.qw3rtrun.p3d.g.marlin.event.SdDoneSaving;
import org.qw3rtrun.p3d.g.marlin.event.SdFile;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.g.marlin.event.SdFileListBegin;
import org.qw3rtrun.p3d.g.marlin.event.SdFileListEnd;
import org.qw3rtrun.p3d.g.marlin.event.SdFileOpened;
import org.qw3rtrun.p3d.g.marlin.event.SdFileSelected;
import org.qw3rtrun.p3d.g.marlin.event.SdFreshFile;
import org.qw3rtrun.p3d.g.marlin.event.SdNotPrinting;
import org.qw3rtrun.p3d.g.marlin.event.SdPrinting;
import org.qw3rtrun.p3d.g.marlin.event.SdWritingToFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the card is, as the replies that would have announced it - and what it is not.
 */
class SdCardControlTest {

    private static final SdFileList TWO_FILES = new SdFileList(List.of(
            new SdFile("TEST.GCO", 47), new SdFile("BENCHY.GCO", 1245678)));

    private final SdCardControl card = new SdCardControl();

    /** Nothing said is nothing known: an unknown card is not an empty one, nor an absent one. */
    @Test
    void aCardNobodyAskedAboutIsNotInTheSnapshot() {
        assertEquals(List.of(), card.snapshot());
    }

    @Test
    void aMountedCardWithAListingIsBoth() {
        card.on(SdCardOk.INSTANCE);
        card.on(SdFileListBegin.INSTANCE);
        card.on(TWO_FILES);
        card.on(SdFileListEnd.INSTANCE);

        assertEquals(List.of(SdCardOk.INSTANCE, TWO_FILES), card.snapshot());
    }

    /** A listing is proof of a card, which covers a machine that was mounted before the host came. */
    @Test
    void aListingAloneMountsTheCard() {
        card.on(TWO_FILES);

        assertEquals(List.of(SdCardOk.INSTANCE, TWO_FILES), card.snapshot());
    }

    /** The newest listing replaces the last, rather than being merged into it. */
    @Test
    void theLatestListingIsTheListing() {
        card.on(TWO_FILES);
        var one = new SdFileList(List.of(new SdFile("KEEP.GCO", 10)));
        card.on(one);

        assertEquals(List.of(SdCardOk.INSTANCE, one), card.snapshot());
    }

    /** {@code M23}/{@code M32}: the file opened is the file selected, announced as the pair it was. */
    @Test
    void aSelectedFileIsTheOpenedFileAndItsConfirmation() {
        card.on(new SdFreshFile("test.gco"));
        card.on(new SdFileOpened("test.gco", 47));
        card.on(SdFileSelected.INSTANCE);

        assertEquals(List.of(SdCardOk.INSTANCE, new SdFileOpened("test.gco", 47), SdFileSelected.INSTANCE),
                card.snapshot());
    }

    /** {@code M28} until {@code M29}: a file being written is state, its end is not. */
    @Test
    void aFileIsBeingWrittenUntilItIsSaved() {
        card.on(new SdWritingToFile("test.gco"));
        assertEquals(List.of(SdCardOk.INSTANCE, new SdWritingToFile("test.gco")), card.snapshot());

        card.on(SdDoneSaving.INSTANCE);
        assertEquals(List.of(SdCardOk.INSTANCE), card.snapshot());
    }

    /** {@code M27}, and the unsolicited end of a print, which reads as not printing afterwards. */
    @Test
    void aPrintRunsUntilItIsDone() {
        card.on(new SdPrinting(1234, 56789));
        assertEquals(List.of(SdCardOk.INSTANCE, new SdPrinting(1234, 56789)), card.snapshot());

        card.on(SdDonePrinting.INSTANCE);
        assertEquals(List.of(SdCardOk.INSTANCE, SdNotPrinting.INSTANCE), card.snapshot());
    }

    @Test
    void theFileBeingPrintedIsKept() {
        card.on(new SdCurrentFile("TEST.GCO"));

        assertEquals(List.of(new SdCurrentFile("TEST.GCO")), card.snapshot());
    }

    /** {@code M22} sent: a released card's contents must not be handed to the next page. */
    @Test
    void releasingTheCardForgetsIt() {
        card.on(TWO_FILES);
        card.released();

        assertEquals(List.of(), card.snapshot());
    }

    /** A new session may be a different card: nothing the last one learned is vouched for. */
    @Test
    void aNewSessionForgetsTheCard() {
        card.on(TWO_FILES);
        card.on(new SdPrinting(1, 2));
        card.sessionStarted();

        assertEquals(List.of(), card.snapshot());
    }
}
