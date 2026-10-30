package org.qw3rtrun.p3d.g.marlin.event

import org.qw3rtrun.p3d.g.protocol.GRsDecoder
import org.qw3rtrun.p3d.g.protocol.asciiTrim
import org.qw3rtrun.p3d.g.protocol.digitsEnd
import org.qw3rtrun.p3d.g.protocol.equalsAscii

/**
 * `M20`'s answer: the printable files on the card, as a block of lines.
 *
 * ```
 * Begin file list
 * TEST.GCO 47
 * BENCHY.GCO 2847561
 * End file list
 * ```
 *
 * **This is the reply that forced the multi-line decision**, and the shape of the answer is not the
 * one `M119` got. `EndstopRs` models its block as one event per line because each line stands on
 * its own - `x_min: open` means the same thing whether or not its header arrived. A file list does
 * not: a list without its terminator is *wrong* rather than merely partial, because a host cannot
 * tell a card holding two files from a card holding two hundred whose listing was cut off.
 *
 * So the three line kinds are split by what each can honestly claim on its own:
 *
 * - [SdFileListBegin] and [SdFileListEnd] are ordinary decoders, reached through [SdCardRsDecoder]
 *   like the rest of the family. Both are fixed literals, so neither can be mistaken for anything
 *   else.
 * - **An entry is not a registry decoder**, and that is the whole design. `TEST.GCO 47` is
 *   `<word> <number>`, which is far too common a shape on this wire to claim - the same objection
 *   `EndstopStateRs` records about `<word>: <word>`, where `busy: processing` fits the pattern.
 *   Nothing may read an entry except something that already knows a list is open, which is
 *   [SdFileListReader].
 * - [SdFileList] is the assembled block, produced only by that reader and only on a terminator.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
object SdFileListBegin : SdCardRs<SdFileListBegin>, GRsDecoder<SdFileListBegin> {

    override fun encode(): String = "Begin file list"

    override fun decode(line: String): SdFileListBegin? =
        if (line.equalsAscii("Begin file list")) this else null
}

/**
 * `End file list` - the terminator that makes the block above it a whole answer.
 *
 * ```
 * SdFileListEnd.decode("End file list")   // SdFileListEnd
 * ```
 */
object SdFileListEnd : SdCardRs<SdFileListEnd>, GRsDecoder<SdFileListEnd> {

    override fun encode(): String = "End file list"

    override fun decode(line: String): SdFileListEnd? =
        if (line.equalsAscii("End file list")) this else null
}

/**
 * One entry in a listing: the name as the card holds it, and the size in bytes.
 *
 * **A value and not a reply.** It has no decoder of its own and never reaches the reply bus by
 * itself - see [SdFileListBegin] for why a bare `<word> <number>` must not be claimed. It exists
 * only inside a [SdFileList].
 *
 * The name is whatever the firmware wrote, which on a build without long-filename support is DOS
 * 8.3 and upper case: a file uploaded as `test.gco` is listed as `TEST.GCO`. That is the card's
 * spelling and it is kept rather than normalised, because it is also the name `M23` and `M32` take.
 *
 * ```
 * SdFile("TEST.GCO", 47)
 * ```
 *
 * @property name the file's name, exactly as the listing spelled it
 * @property size the file's size in bytes
 */
data class SdFile(val name: String, val size: Long)

/**
 * A whole `M20` listing, assembled: every file the card holds, in the order it listed them.
 *
 * **It carries no partial state.** A [SdFileListReader] produces one only when it has seen the
 * terminator, so holding one of these means the listing is complete - which is the property the
 * one-event-per-line reading could not offer.
 *
 * **[encode] writes the whole block**, terminators included, and is therefore the one reply in this
 * module whose text is more than one line. That is a deliberate exception rather than an oversight:
 * the value *is* a block, and encoding it as its first line would be a lie. It still round-trips,
 * just not through a `GRsDecoder` - feed the lines back through a reader and the same value comes
 * out, which `SdFileListTest` asserts.
 *
 * ```
 * SdFileList(listOf(SdFile("TEST.GCO", 47))).encode()
 * // Begin file list
 * // TEST.GCO 47
 * // End file list
 * ```
 *
 * @property files the listing's entries, in wire order; empty for a card with nothing printable
 */
data class SdFileList(val files: List<SdFile>) : SdCardRs<SdFileList> {

    override fun encode(): String {
        val out = StringBuilder()
        out.append(SdFileListBegin.encode())
        for (file in files) {
            out.append('\n').append(file.name).append(' ').append(file.size)
        }
        out.append('\n').append(SdFileListEnd.encode())
        return out.toString()
    }
}

/**
 * Reads an `M20` listing out of the lines a printer sends, one line at a time.
 *
 * **This is the module's answer to the multi-line problem**, and it is deliberately the *only*
 * stateful thing in the reply layer. Every `GRsDecoder` is a pure function from one line to one
 * value and stays that way; a decoder that remembered the line before it would be a decoder that
 * two printers could not share, and `MarlinRsDecoder` is a singleton registry.
 *
 * So the memory lives here instead, in an object a caller creates and owns:
 *
 * ```
 * val reader = SdFileListReader()
 * reader.read("Begin file list")    // null - open, nothing complete yet
 * reader.read("TEST.GCO 47")        // null
 * reader.read("End file list")      // SdFileList([SdFile("TEST.GCO", 47)])
 * ```
 *
 * **One reader per connection, and every call on the owner's one thread.** It holds a plain
 * `ArrayList` and no atomics - the same contract `GSendWindow` states for the same reason, and for
 * the same reason it is written here rather than on whatever ends up calling it: this file travels
 * to a port and the caller does not.
 *
 * **A line it does not recognise while a list is open is an entry, and one it cannot read as an
 * entry abandons the list.** That is the strict reading, and it is the right one: a listing
 * interrupted by something else is a listing this host cannot vouch for, and reporting a short list
 * as if it were the card's contents is the failure that matters. Abandoning says so by answering
 * null to the terminator that eventually arrives.
 */
class SdFileListReader {

    private var open = false

    private var abandoned = false

    private val files = ArrayList<SdFile>()

    /**
     * Offers one received line to the reader.
     *
     * @param line one line as the printer sent it
     * @return the completed listing when [line] terminated one, and null every other time -
     *   including for a terminator that closes a listing something interrupted
     */
    fun read(line: String): SdFileList? {
        if (SdFileListBegin.decode(line) != null) {
            // A second `Begin` without an `End` means the first listing will never be completed.
            // Start again rather than merge them: the entries before it belong to an answer that
            // was cut off, and a host cannot tell which half of the result it would be reporting.
            open = true
            abandoned = false
            files.clear()
            return null
        }
        if (!open) return null

        if (SdFileListEnd.decode(line) != null) {
            open = false
            if (abandoned) {
                files.clear()
                return null
            }
            val list = SdFileList(ArrayList(files))
            files.clear()
            return list
        }

        val file = entry(line)
        if (file == null) {
            abandoned = true
            return null
        }
        files.add(file)
        return null
    }

    /** Whether a listing is open - a caller that times one out needs to know. */
    fun isReading(): Boolean = open

    private companion object {

        /**
         * `<name> <size>`, or null when the line is not one.
         *
         * The name runs to the last blank rather than the first, because a long-filename build
         * writes names with spaces in them and the size is always the final field. A line with no
         * blank at all, or whose tail is not a run of digits, is not an entry.
         *
         * Spec 9: a size too wide for a `Long` is malformed input off the wire, so the line is not
         * an entry rather than the reader throwing over it.
         */
        fun entry(line: String): SdFile? {
            val text = line.asciiTrim()
            if (text.isEmpty()) return null

            var at = text.length
            while (at > 0 && text[at - 1] != ' ' && text[at - 1] != '\t') at--
            if (at == 0) return null

            val digits = text.digitsEnd(at)
            if (digits == at || digits != text.length) return null
            val size = text.substring(at, digits).toLongOrNull() ?: return null

            var nameEnd = at
            while (nameEnd > 0 && (text[nameEnd - 1] == ' ' || text[nameEnd - 1] == '\t')) nameEnd--
            if (nameEnd == 0) return null

            return SdFile(text.substring(0, nameEnd), size)
        }
    }
}
