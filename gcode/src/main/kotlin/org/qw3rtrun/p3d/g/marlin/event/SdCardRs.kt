package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.GEventRs
import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.blanksEnd
import org.qw3rtrun.p3d.g.protocol.digitsEnd
import org.qw3rtrun.p3d.g.protocol.equalsAscii
import org.qw3rtrun.p3d.g.protocol.startsWithAscii

/**
 * Anything the machine says about its card: the status reports, the listing, and the lines the
 * firmware writes as a file is opened, saved, selected and finished.
 *
 * **One interface over the whole family, because a host managing storage wants one subscription.**
 * The alternative is a consumer that enumerates a dozen classes and silently misses the thirteenth
 * the day it is added. `SdStatusRs` ([SdPrinting], [SdNotPrinting], [SdCurrentFile]) and the
 * listing types ([SdFileListBegin], [SdFileList]) are under it too, so matching `SdCardRs<*>`
 * catches everything on this subject and nothing else.
 *
 * Five of these are also [EchoRs] - [SdCardOk], [SdFreshFile], [SdDoingFile], [SdSubroutineCall]
 * and [SdSubroutineEnd] arrive with the `echo:` prefix, because the firmware writes them through
 * `SERIAL_ECHO_MSG`. That is the same both-at-once shape [BusyNotice] has, and for the same reason:
 * *what* a reply reports and *how* it arrives are two different questions, and a type can answer
 * both.
 *
 * Every line here was captured from MarlinSim (bugfix-2.1.x). The first session is
 * `backend/terminal/.../SdWriteProbe.java`, which writes a file with `M28`/`M29`, lists it, and
 * prints it, in order:
 *
 * ```
 * >> M28 test.gco       << echo:Now fresh file: test.gco     SdFreshFile
 *                       << Writing to file: test.gco         SdWritingToFile
 *                       << //action:notification test.gco    ActionRs
 * >> M29                << Done saving file.                 SdDoneSaving
 * >> M20                << Begin file list                   SdFileListBegin
 *                       << TEST.GCO 47                       (an SdFile, inside the listing)
 *                       << End file list                     SdFileListEnd
 * >> M32 test.gco       << echo:Now fresh file: test.gco     SdFreshFile
 *                       << File opened: test.gco Size: 47    SdFileOpened
 *                       << File selected                     SdFileSelected
 *    ...while printing  << Done printing file                SdDonePrinting
 * >> M21                << echo:SD card ok                   SdCardOk
 * ```
 *
 * **That `M32 test.gco` is the *lucky* reading of the command, not the only one.** `M32` is not in
 * Marlin's string-argument special case, so its line is parsed for parameters before what is left
 * is taken as the filename: a `p` anywhere in the name arrives as a bare `P`, which means "call
 * this file as a subroutine". `test.gco` has no `p`; `cup.gco` has. `SdPrintProbe` sent the same
 * command with a name that does, on 2026-11-10, with `live.gco` already open from an `M23`:
 *
 * ```
 * >> M32 cup.gco        << echo:SUBROUTINE CALL target:"cup.gco"
 *                                              parent:"/LIVE.GCO" pos0    SdSubroutineCall
 *                       << File opened: cup.gco Size: 21                  SdFileOpened
 *                       << File selected                                  SdFileSelected
 *    ...cup.gco runs    << echo:END SUBROUTINE                            SdSubroutineEnd
 *                       << File opened: LIVE.GCO Size: 22                 SdFileOpened
 *                       << File selected                                  SdFileSelected
 *    ...live.gco runs   << Done printing file                             SdDonePrinting
 * ```
 *
 * With nothing open to return to, the same command says only `echo:Now doing file: cup.gco`
 * ([SdDoingFile]) and the rest of the answer is `M23`'s. So two files printed, one `Done printing
 * file` ended them, and the only warning was an `echo:`.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
interface SdCardRs<D : SdCardRs<D>> : GEventRs<D>

/**
 * `Writing to file: <name>` - `M28` was accepted and every line that follows goes into the file.
 *
 * **The most consequential reply in this family**, because it changes what the wire means: until an
 * `M29` arrives, a line a host sends is stored rather than executed. A host that missed this and
 * carried on streaming a job would write the job into a file and run none of it.
 *
 * It is also where numbering starts mattering absolutely. A bare line while saving is refused with
 * `Error:No Checksum with line number, Last Line: <n>` - the probe measured it - and `M28` does
 * **not** reset the counter, so the numbering a session was already using carries straight on.
 *
 * ```
 * SdWritingToFile.decode("Writing to file: test.gco")   // SdWritingToFile("test.gco")
 * ```
 */
data class SdWritingToFile(val file: String) : SdCardRs<SdWritingToFile> {

    override fun encode(): String = PREFIX + file

    companion object : GRsDecoder<SdWritingToFile> {

        private const val PREFIX = "Writing to file: "

        /** The filename behind the prefix, or null when the line is not this reply. */
        override fun decode(line: String): SdWritingToFile? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(PREFIX)) return null

            val name = text.substring(PREFIX.length).asciiTrim()
            return if (name.isEmpty()) null else SdWritingToFile(name)
        }
    }
}

/**
 * `Done saving file.` - `M29` closed the file, and the wire is executing lines again.
 *
 * The other half of [SdWritingToFile], and the one a host waits for before it resumes streaming.
 * Note the full stop, which the firmware writes and [SdDonePrinting] does not.
 *
 * ```
 * SdDoneSaving.decode("Done saving file.")   // SdDoneSaving
 * ```
 */
object SdDoneSaving : SdCardRs<SdDoneSaving>, GRsDecoder<SdDoneSaving> {

    override fun encode(): String = "Done saving file."

    override fun decode(line: String): SdDoneSaving? =
        if (line.equalsAscii("Done saving file.")) this else null
}

/**
 * `File opened: <name> Size: <bytes>` - the first half of `M23`/`M32`'s answer.
 *
 * The size is the firmware's own measure of what is on the card, so it is the figure to show and to
 * divide `M27`'s position by. For a file written through `M28` it is the payloads plus a `CRLF`
 * each: the probe's four content lines came back as 47 bytes, which is what says the stored lines
 * carry **no** `N` word and no checksum - the firmware strips both before writing.
 *
 * **The name is read back from `Size:`, not forward from the first blank.** A build with
 * `LONG_FILENAME_HOST_SUPPORT` writes names with spaces in them, and the marker is the only fixed
 * point in the line. Same rule as an [SdFile] entry, where the size is the last field.
 *
 * ```
 * SdFileOpened.decode("File opened: test.gco Size: 47")   // SdFileOpened("test.gco", 47)
 * ```
 */
data class SdFileOpened(val file: String, val size: Long) : SdCardRs<SdFileOpened> {

    override fun encode(): String = PREFIX + file + SIZE + size

    companion object : GRsDecoder<SdFileOpened> {

        private const val PREFIX = "File opened: "

        private const val SIZE = " Size: "

        /**
         * The name and the size, or null when the line is not one of these.
         *
         * Scans for the **last** ` Size: ` rather than the first, so a file actually called
         * `my Size: 1.gco` keeps its name. Spec 9: a size too wide for a `Long` is malformed input
         * off the wire, so the line does not decode rather than throwing.
         */
        override fun decode(line: String): SdFileOpened? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(PREFIX)) return null

            var marker = -1
            var at = PREFIX.length
            while (at < text.length) {
                if (text.startsWithAscii(SIZE, at)) marker = at
                at++
            }
            if (marker <= PREFIX.length) return null

            val from = text.blanksEnd(marker + SIZE.length)
            val end = text.digitsEnd(from)
            if (end == from || end != text.length) return null
            val size = text.substring(from, end).toLongOrNull() ?: return null

            return SdFileOpened(text.substring(PREFIX.length, marker), size)
        }
    }
}

/**
 * `File selected` - the second half of `M23`/`M32`'s answer: the card is positioned at the start.
 *
 * After `M32` the print is already running; after `M23` it waits for `M24`. The reply is the same
 * either way, which is why it says nothing about printing.
 *
 * ```
 * SdFileSelected.decode("File selected")   // SdFileSelected
 * ```
 */
object SdFileSelected : SdCardRs<SdFileSelected>, GRsDecoder<SdFileSelected> {

    override fun encode(): String = "File selected"

    override fun decode(line: String): SdFileSelected? =
        if (line.equalsAscii("File selected")) this else null
}

/**
 * `Done printing file` - the card reached the end of the job.
 *
 * **Unbidden, and the only notice that the print finished.** Nothing asked for it: it arrives in
 * the middle of the auto-report traffic, long after the `ok` that answered `M32`. A host that only
 * read replies to its own commands would never see it, which is the case for decoding the whole
 * stream rather than just the answers.
 *
 * No full stop, unlike [SdDoneSaving]. The two messages reach the line through different calls in
 * the firmware, and the difference is kept rather than tidied away - a reply type that re-encoded
 * to a line its printer does not write would be a worse lie than an inconsistency.
 *
 * ```
 * SdDonePrinting.decode("Done printing file")   // SdDonePrinting
 * ```
 */
object SdDonePrinting : SdCardRs<SdDonePrinting>, GRsDecoder<SdDonePrinting> {

    override fun encode(): String = "Done printing file"

    override fun decode(line: String): SdDonePrinting? =
        if (line.equalsAscii("Done printing file")) this else null
}

/**
 * `echo:SD card ok` - `M21` mounted the card and it answered.
 *
 * **The one line that says storage is usable at all**, so it is the one a Storage view is waiting
 * for. It used to land as undifferentiated [EchoMessage] prose, which meant a host had to compare
 * against that text by hand to learn whether there was a card.
 *
 * Both an [SdCardRs] and an [EchoRs], because `M21` reports through `SERIAL_ECHO_MSG` - see
 * [SdCardRs] on why a reply gets to be both.
 *
 * ```
 * SdCardOk.decode("echo:SD card ok")   // SdCardOk
 * ```
 */
object SdCardOk : SdCardRs<SdCardOk>, EchoRs<SdCardOk>, GRsDecoder<SdCardOk> {

    override fun encode(): String = EchoMessage.PREFIX + "SD card ok"

    override fun decode(line: String): SdCardOk? =
        if (line.equalsAscii(EchoMessage.PREFIX + "SD card ok")) this else null
}

/**
 * `echo:Now fresh file: <name>` - the firmware opened a file and is at byte zero of it.
 *
 * Written by both `M28` and `M32`, ahead of [SdWritingToFile] and [SdFileOpened] respectively, so
 * on its own it does not say which is about to happen - it says a file was opened, and the line
 * behind it says what for. Kept as its own type anyway because it carries the name the firmware
 * resolved, which for a card with short filenames is not always the name that was asked for.
 *
 * **`M32` writes it only for a name the firmware does not read as a subroutine call.** `fresh` is
 * `announceOpen()`'s word for the job itself; a name containing a `p` is parsed as `M32 P <name>`
 * and answered [SdDoingFile] or [SdSubroutineCall] instead. Seeing this line is therefore the
 * reassuring case - the print that starts is the one that was asked for.
 *
 * ```
 * SdFreshFile.decode("echo:Now fresh file: test.gco")   // SdFreshFile("test.gco")
 * ```
 */
data class SdFreshFile(val file: String) : SdCardRs<SdFreshFile>, EchoRs<SdFreshFile> {

    override fun encode(): String = EchoMessage.PREFIX + BODY + file

    companion object : GRsDecoder<SdFreshFile> {

        private const val BODY = "Now fresh file: "

        /** The filename behind the prefix, or null when the line is not this reply. */
        override fun decode(line: String): SdFreshFile? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(EchoMessage.PREFIX)) return null

            val at = text.blanksEnd(EchoMessage.PREFIX.length)
            if (!text.startsWithAscii(BODY, at)) return null

            val name = text.substring(at + BODY.length).asciiTrim()
            return if (name.isEmpty()) null else SdFreshFile(name)
        }
    }
}

/**
 * `echo:Now doing file: <name>` - the firmware opened a file **as a subroutine**, not as the print
 * that was asked for.
 *
 * One word apart from [SdFreshFile] and a different event: Marlin's `announceOpen()` writes `doing`
 * when the file is being run as a procedure and `fresh` when it is the job itself. So this is a
 * line that says a print nobody asked for is starting - a bare `M32 <name>` whose name contains a
 * `p` is read as `M32 P <name>`, and `P` means "call this file as a subroutine". When a file was
 * already open the firmware says so in full, as [SdSubroutineCall]; with nothing to return to, this
 * is all it says.
 *
 * `SdPrintProbe` against MarlinSim (bugfix-2.1.x), 2026-11-10, varying only the name: `live.gco`
 * and `set.gco` answered `echo:Now fresh file:`, `prt.gco` and `cup.gco` answered this - a `p`
 * anywhere in the name is the trigger, and `s` is harmless because `S` needs a value and `P` does
 * not.
 *
 * ```
 * SdDoingFile.decode("echo:Now doing file: cup.gco")   // SdDoingFile("cup.gco")
 * ```
 */
data class SdDoingFile(val file: String) : SdCardRs<SdDoingFile>, EchoRs<SdDoingFile> {

    override fun encode(): String = EchoMessage.PREFIX + BODY + file

    companion object : GRsDecoder<SdDoingFile> {

        private const val BODY = "Now doing file: "

        /** The filename behind the prefix, or null when the line is not this reply. */
        override fun decode(line: String): SdDoingFile? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(EchoMessage.PREFIX)) return null

            val at = text.blanksEnd(EchoMessage.PREFIX.length)
            if (!text.startsWithAscii(BODY, at)) return null

            val name = text.substring(at + BODY.length).asciiTrim()
            return if (name.isEmpty()) null else SdDoingFile(name)
        }
    }
}

/**
 * `echo:SUBROUTINE CALL target:"<target>" parent:"<parent>" pos<n>` - a second print is starting
 * inside the one already running, and the firmware will come back to [parent] at byte [position].
 *
 * **The only line on the wire that announces a print nobody asked for.** It is what an `M32` whose
 * filename contains a `p` produces while a file is open: the line is parsed for parameters before
 * what is left is taken as the filename, a bare `P` means "call as a subroutine", and the firmware
 * prints the target, writes [SdSubroutineEnd], reopens the parent and prints that too. Both prints
 * end in one `Done printing file`, so a host counting completions sees one job where two ran.
 *
 * Captured by `SdPrintProbe` against MarlinSim (bugfix-2.1.x), 2026-11-10 - `M23 live.gco` to leave
 * a parent open at byte 0, then `N39 M32 cup.gco*43`:
 *
 * ```
 * << echo:SUBROUTINE CALL target:"cup.gco" parent:"/LIVE.GCO" pos0
 * << File opened: cup.gco Size: 21
 * << echo:END SUBROUTINE
 * << File opened: LIVE.GCO Size: 22
 * << RAN-cup.gco
 * << RAN-live.gco
 * << Done printing file
 * ```
 *
 * The parent is the firmware's own resolved path and the position is where it will resume, so the
 * three fields together are what a host needs to say what is about to happen. `pos` carries no
 * separator at all, which is the firmware's spelling and is kept.
 *
 * ```
 * SdSubroutineCall.decode("echo:SUBROUTINE CALL target:\"cup.gco\" parent:\"/LIVE.GCO\" pos0")
 * // SdSubroutineCall("cup.gco", "/LIVE.GCO", 0)
 * ```
 *
 * @property target the file being called
 * @property parent the file it will return to
 * @property position the byte in [parent] the firmware left off at
 */
data class SdSubroutineCall(
    val target: String,
    val parent: String,
    val position: Long,
) : SdCardRs<SdSubroutineCall>, EchoRs<SdSubroutineCall> {

    override fun encode(): String =
        EchoMessage.PREFIX + BODY + target + PARENT + parent + POS + position

    companion object : GRsDecoder<SdSubroutineCall> {

        private const val BODY = "SUBROUTINE CALL target:\""

        private const val PARENT = "\" parent:\""

        private const val POS = "\" pos"

        /**
         * The two names and the position, or null when the line is not one of these.
         *
         * Takes the **last** of each marker rather than the first, the rule [SdFileOpened] reads a
         * size by and for the same reason: with `LONG_FILENAME_HOST_SUPPORT` a name can carry
         * blanks, so the markers are the only fixed points in the line. Spec 9: a position that is
         * not digits, or one too wide for a `Long`, is malformed input off the wire, so the line
         * does not decode rather than throwing.
         *
         * @param line one received line
         * @return the call, or null when [line] is some other kind
         */
        override fun decode(line: String): SdSubroutineCall? {
            val text = line.asciiTrim()
            if (!text.startsWithAscii(EchoMessage.PREFIX)) return null

            val head = text.blanksEnd(EchoMessage.PREFIX.length)
            if (!text.startsWithAscii(BODY, head)) return null

            val target = head + BODY.length
            var parent = -1
            var pos = -1
            var at = target
            while (at < text.length) {
                if (text.startsWithAscii(PARENT, at)) parent = at
                else if (text.startsWithAscii(POS, at)) pos = at
                at++
            }
            if (parent <= target || pos <= parent + PARENT.length) return null

            val digits = pos + POS.length
            val end = text.digitsEnd(digits)
            if (end == digits || end != text.length) return null
            val position = text.substring(digits, end).toLongOrNull() ?: return null

            return SdSubroutineCall(
                text.substring(target, parent),
                text.substring(parent + PARENT.length, pos),
                position,
            )
        }
    }
}

/**
 * `echo:END SUBROUTINE` - the called file reached its end and the parent print is resuming.
 *
 * The closing half of [SdSubroutineCall], and the point from which the file lines that follow -
 * another `File opened:` and `File selected` - are the *parent* being reopened rather than a new
 * job starting. Carries nothing, because the names were in the call.
 *
 * ```
 * SdSubroutineEnd.decode("echo:END SUBROUTINE")   // SdSubroutineEnd
 * ```
 */
object SdSubroutineEnd : SdCardRs<SdSubroutineEnd>, EchoRs<SdSubroutineEnd>, GRsDecoder<SdSubroutineEnd> {

    override fun encode(): String = EchoMessage.PREFIX + "END SUBROUTINE"

    override fun decode(line: String): SdSubroutineEnd? =
        if (line.equalsAscii(EchoMessage.PREFIX + "END SUBROUTINE")) this else null
}

/**
 * Everything the card says: `M27`'s status, `M20`'s listing markers, and the file lifecycle.
 *
 * The two `echo:`-prefixed members are **not** here - [SdCardOk] and [SdFreshFile] are reached
 * through `EchoRsDecoder`, which owns that prefix and partitions it. Putting them in both places
 * would give the registry two claimants for one line, which is the property `MarlinRsTest` pins.
 *
 * ```
 * SdCardRsDecoder.decode("Done saving file.")   // SdDoneSaving
 * SdCardRsDecoder.decode("echo:SD card ok")     // null - EchoRsDecoder claims it
 * ```
 */
object SdCardRsDecoder : GRsDecoder<SdCardRs<*>> {

    private val decoders: List<GRsDecoder<SdCardRs<*>>> = listOf(
        SdPrinting,
        SdCurrentFile,
        SdNotPrinting,
        SdFileListBegin,
        SdFileListEnd,
        SdWritingToFile,
        SdDoneSaving,
        SdFileOpened,
        SdFileSelected,
        SdDonePrinting,
    )

    override fun decode(line: String): SdCardRs<*>? = decoders.firstNotNullOfOrNull { it.decode(line) }
}
