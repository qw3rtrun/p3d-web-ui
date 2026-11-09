package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.g.marlin.event.SdCardOk;
import org.qw3rtrun.p3d.g.marlin.event.SdCardRs;
import org.qw3rtrun.p3d.g.marlin.event.SdCurrentFile;
import org.qw3rtrun.p3d.g.marlin.event.SdDonePrinting;
import org.qw3rtrun.p3d.g.marlin.event.SdDoneSaving;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.g.marlin.event.SdFileOpened;
import org.qw3rtrun.p3d.g.marlin.event.SdFileSelected;
import org.qw3rtrun.p3d.g.marlin.event.SdNotPrinting;
import org.qw3rtrun.p3d.g.marlin.event.SdPrinting;
import org.qw3rtrun.p3d.g.marlin.event.SdStatusRs;
import org.qw3rtrun.p3d.g.marlin.event.SdWritingToFile;

import java.util.ArrayList;
import java.util.List;

/**
 * What this machine's card is, as far as its replies have said: mounted, what is on it, which file
 * is selected, what is being written, whether it is printing. Same shape as
 * {@link TemperatureControl} and {@link ProfileControl}: {@link #on} folds a reply in,
 * {@link #snapshot()} hands the state back as the replies that announced it, so a late Storage tab
 * shows the card as it is rather than an empty table until somebody presses "list".
 *
 * <p>Every part is "the last thing the machine said" and nothing is inferred from a command having
 * been sent (a listing is the last {@code M20} answer, not that answer edited by a later
 * {@code M30}) - except {@link #released()}, whose reason is written there. What the machine has
 * not said is absent, not empty. A happening - {@code Done saving file.}, {@code Done printing
 * file}, {@code Now fresh file:}, the listing's begin/end markers - changes the state and is never
 * itself handed to a late subscriber.
 *
 * <p>One immutable value behind one volatile reference, not separate fields, because the parts
 * belong together: a snapshot must not pair a listing with a mount flag from before it.
 */
public class SdCardControl {

    /** The card's state. Each part is null when the machine has not said. */
    record State(boolean mounted,
                 SdFileList listing,
                 SdFileOpened selected,
                 SdWritingToFile writing,
                 SdStatusRs<?> printing,
                 SdCurrentFile current) {

        static final State UNKNOWN = new State(false, null, null, null, null, null);
    }

    private volatile State state = State.UNKNOWN;

    State state() {
        return state;
    }

    /**
     * One reply about the card, folded into what is known about it.
     *
     * <p>A listing, an opened file and a file being written are each proof of a mounted card, so
     * they mount it here too - the same inference the Storage tab draws from a listing.
     */
    public void on(SdCardRs<?> reply) {
        var s = state;
        state = switch (reply) {
            case SdCardOk ignored -> new State(true, s.listing(), s.selected(), s.writing(), s.printing(), s.current());
            case SdFileList listing -> new State(true, listing, s.selected(), s.writing(), s.printing(), s.current());
            case SdFileOpened opened -> new State(true, s.listing(), opened, s.writing(), s.printing(), s.current());
            case SdWritingToFile writing -> new State(true, s.listing(), s.selected(), writing, s.printing(), s.current());
            case SdDoneSaving ignored -> new State(s.mounted(), s.listing(), s.selected(), null, s.printing(), s.current());
            case SdPrinting printing -> new State(true, s.listing(), s.selected(), s.writing(), printing, s.current());
            case SdNotPrinting notPrinting -> new State(s.mounted(), s.listing(), s.selected(), s.writing(), notPrinting, s.current());
            case SdDonePrinting ignored -> new State(s.mounted(), s.listing(), s.selected(), s.writing(), SdNotPrinting.INSTANCE, s.current());
            case SdCurrentFile current -> new State(s.mounted(), s.listing(), s.selected(), s.writing(), s.printing(), current);
            // SdFileSelected confirms the SdFileOpened before it; SdFreshFile and the listing's
            // markers are steps on the way to a state rather than part of one.
            default -> s;
        };
    }

    /**
     * {@code M22} was sent, so nothing about the card can be vouched for any more.
     *
     * <p><strong>The one part of this class that follows a command rather than a reply</strong>,
     * because {@code :gcode} has no type for Marlin's {@code echo:SD card released} - it arrives as
     * a plain echo. Keeping a released card's listing would hand every page opened afterwards a card
     * that is not there; forgetting it costs a page one "list" press if the release was refused.
     * When the release reply gets a type, this should move to {@link #on}.
     */
    public void released() {
        state = State.UNKNOWN;
    }

    /**
     * A new session on the wire: whatever was known about the card belonged to the last one.
     *
     * <p>A link that was down may have been down because the machine was switched off, and a card
     * can be swapped while it is. Nothing the previous session learned survives that, so a new one
     * starts from "not known" and learns it again from what the machine says - which
     * {@code PrinterState.onOnline} announces as a {@code SessionStarted}, so a subscriber already
     * watching is told too.
     */
    public void sessionStarted() {
        state = State.UNKNOWN;
    }

    /**
     * The card as the replies that would have announced it, in the order a page builds on them:
     * mounted, its contents, the selected file, a file being written, the print status, the file
     * being printed.
     */
    public List<Object> snapshot() {
        var s = state;
        var replies = new ArrayList<Object>();
        if (s.mounted()) {
            replies.add(SdCardOk.INSTANCE);
        }
        if (s.listing() != null) {
            replies.add(s.listing());
        }
        if (s.selected() != null) {
            replies.add(s.selected());
            replies.add(SdFileSelected.INSTANCE);
        }
        if (s.writing() != null) {
            replies.add(s.writing());
        }
        if (s.printing() != null) {
            replies.add(s.printing());
        }
        if (s.current() != null) {
            replies.add(s.current());
        }
        return replies;
    }
}
