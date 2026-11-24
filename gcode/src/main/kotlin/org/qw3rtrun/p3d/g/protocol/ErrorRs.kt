package org.qw3rtrun.p3d.g.protocol


/**
 * The three prefixes an error reply is written with, and the lexeme each one writes:
 *
 * - `BANGS` - `!!`, the original two-character error prefix.
 * - `ERROR` - `Error:`, what Marlin and RepRapFirmware send.
 * - `FATAL` - `fatal:`, Repetier Firmware.
 *
 * ```
 * ErrorPrefix.ERROR.lexeme   // "Error:"
 * ```
 *
 * @property lexeme the prefix exactly as it is written on the wire
 */
enum class ErrorPrefix(val lexeme: String) {
    BANGS("!!"),

    ERROR("Error:"),

    FATAL("fatal:"),
}

/**
 * `!!` / `Error:` / `fatal:` - something went wrong, with the machine's own words for it.
 *
 * ```
 * Error:checksum mismatch, Last Line: 66555
 * !! hardware fault
 * ```
 *
 * The message is kept as text and is not classified here: the spec sorts errors into recoverable
 * communication errors (followed immediately by a [ResendRs]), non-fatal errors such as
 * `Unknown command` and the SD card family, and everything else, which it says to treat as a
 * hardware fault that shuts the machine down. That decision is made on prose the spec only gives as
 * examples, and belongs to the host, not a decoder. What this class does promise is [lastLine],
 * since the resend protocol of spec 8.5 is addressed by line number and the number is in the text.
 *
 * On the way out, `!!` is a two-character prefix and takes a space before the message, the way `//`
 * does; `Error:` and `fatal:` end in their own separator, and the spec's example has nothing after
 * it.
 *
 * **Marlin's safety halts - thermal runaway, a heating failure, a MIN/MAXTEMP trigger, a thermal
 * malfunction, heater redundancy mismatch, a watchdog timeout, `kill()` and `stop()` - decode here
 * too, and are not given named subtypes.** `SafetyHaltRsTest` pins one case per message in the
 * catalogue, from `bugfix-2.1.x` sources rather than from the wiki
 * ([issue #171](https://github.com/qw3rtrun/p3d-web-ui/issues/171)), and two of its findings belong
 * here. `STR_STOP_PRE` and `STR_KILL_PRE` *begin with* `!! ` and are written after a
 * `SERIAL_ERROR_START()`, so a real `STOP`/`KILL` line is `Error:!! KILL caused by ...` - [prefix]
 * is [ErrorPrefix.ERROR] and the `!!` stays inside [message], while the bare `!! ...` spelling the
 * wiki lists decodes as [ErrorPrefix.BANGS] with a shorter message. And **cold-extrusion
 * prevention is not an error on this wire**: ` cold extrusion prevented`, ` too long extrusion
 * prevented` and `Hotend too cold` all go out through `SERIAL_ECHO_MSG`, so they arrive under
 * `echo:` and are read by `ColdExtrusionPrevented` and `EchoMessage` - never by this class.
 * [Issue #159](https://github.com/qw3rtrun/p3d-web-ui/issues/159) looked at
 * subtyping them the way [SdCardRs][org.qw3rtrun.p3d.g.marlin.event.SdCardRs] subtypes the card
 * family, and found the two cases are not alike: `SdCardRs`'s members each carry a different
 * structured payload a caller reads by field (a filename, a size, a resume position); every one of
 * these halts is free-text prose per `Marlin/src/core/language.h`'s `STR_T_*`/`STR_WATCHDOG_*`
 * catalogue (`"Thermal Runaway"`, `"Heating failed"`, `"MAXTEMP triggered"`, `"Watchdog timeout.
 * Reset required."`, ...), built the same way the `Last Line:` counter is - a marker and a value
 * inside one sentence - with at most a heater id added the way [lastLine] adds a line number, were a
 * caller ever to need it. `PrinterState.on(ErrorRs)` faults the connection on the message text alone,
 * uniformly, whichever of the three prefixes or which prose sent it; no caller branches on which
 * safety halt occurred. A named subtype earns its keep when a caller needs to act differently on one
 * of these and not the others - until then, one more type nothing consumes is not a free improvement.
 *
 * ```
 * ErrorRs.decode("Error:checksum mismatch, Last Line: 66555")?.lastLine   // 66555
 * ErrorRs(ErrorPrefix.BANGS, "hw").encode()                               // "!! hw"
 * ```
 *
 * @property prefix which of the three prefixes the line was written with
 * @property message the machine's own words, after the prefix, trimmed
 * @property lastLine the line number in a `Last Line: <n>` message, or null when the message does not
 *   name one. Only the `Last Line:` spelling is read. `expected line <n1> got <n2>` names two numbers
 *   with no marker saying which is which, so reading it would be a guess. Spec 9: a number too wide
 *   for `Int` is malformed input, so the field reads as absent.
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
data class ErrorRs(val prefix: ErrorPrefix, val message: String) : GProtoRs<ErrorRs> {

    val lastLine: Int?
        get() {
            // A search, not a match: Marlin writes the counter at the end of a message whose front
            // half differs per error ("checksum mismatch, Last Line: 7"), so the scan walks to the
            // first `last line` and reads the number behind it.
            var at = 0
            while (at + LAST_LINE.length <= message.length) {
                if (!message.startsWithAscii(LAST_LINE, at)) {
                    at++
                    continue
                }
                var i = message.blanksEnd(at + LAST_LINE.length)
                // The `:` is required, as it was in the pattern this replaced: `Last Line 7` with
                // no colon is not Marlin's wording and is not read as the counter.
                if (i < message.length && message[i] == ':') {
                    i = message.blanksEnd(i + 1)
                    val end = message.digitsEnd(i)
                    if (end > i) return message.substring(i, end).toIntOrNull()
                }
                at++
            }
            return null
        }

    override fun encode(): String = when (prefix) {
        ErrorPrefix.BANGS -> if (message.isEmpty()) prefix.lexeme else prefix.lexeme + " " + message
        else -> prefix.lexeme + message
    }

    companion object : GRsDecoder<ErrorRs> {

        private const val LAST_LINE = "last line"

        override fun decode(line: String): ErrorRs? {
            val trimmed = line.asciiTrim()
            val prefix = prefixOf(trimmed) ?: return null
            return ErrorRs(prefix, trimmed.substring(prefix.lexeme.length).asciiTrim())
        }

        private fun prefixOf(trimmed: String): ErrorPrefix? = ErrorPrefix.entries.firstOrNull {
            trimmed.startsWithAscii(it.lexeme)
        }
    }
}
