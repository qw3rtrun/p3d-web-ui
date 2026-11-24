package org.qw3rtrun.p3d.g.marlin.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.g.marlin.event.ColdExtrusionPrevented
import org.qw3rtrun.p3d.g.marlin.event.EchoMessage
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder
import org.qw3rtrun.p3d.g.protocol.ErrorPrefix
import org.qw3rtrun.p3d.g.protocol.ErrorRs

/**
 * Marlin's safety halts - tier 2 of [issue 14](https://github.com/qw3rtrun/p3d-web-ui/issues/14) -
 * through [ErrorRs], one case per message in `Marlin/src/core/language.h`'s `STR_T_*` /
 * `STR_WATCHDOG_*` / `STR_KILL_*` / `STR_STOP_*` catalogue.
 *
 * [Issue 159](https://github.com/qw3rtrun/p3d-web-ui/issues/159) decided these get no named
 * subtypes and decode as free text under one of the three error prefixes;
 * [issue 171](https://github.com/qw3rtrun/p3d-web-ui/issues/171) is this file, which pins that claim
 * message by message instead of asserting it in prose. Every literal here is composed from
 * `bugfix-2.1.x` sources read on 2026-11-24, not from the wiki: the catalogue strings are
 * `core/language.h` 111-270, the prefix each one is sent with is the `SERIAL_ERROR_START()` /
 * `SERIAL_ECHO_START()` call in front of it (`core/serial.cpp` 98-99 - exactly `Error:` and
 * `echo:`), and the sentence each halt is assembled into is `module/temperature.cpp` 1591-1629,
 * `MarlinCore.cpp` 991-1082, `module/probe.cpp` 476-486 and `HAL/AVR/HAL.cpp` 151-158.
 *
 * Three things the reading turned up that the doc comment on [ErrorRs] had asserted without
 * measuring, and that are each a case below:
 *
 * - **`STOP`/`KILL` arrive double-prefixed.** `STR_STOP_PRE` and `STR_KILL_PRE` *begin with* `!! `,
 *   and every site that writes them calls `SERIAL_ERROR_START()` first, so the line on the wire is
 *   `Error:!! KILL caused by ...`. [ErrorRs] reports [ErrorPrefix.ERROR] and keeps the `!!` inside
 *   [ErrorRs.message], because the prefix scan takes the prefix the line actually starts with. The
 *   bare `!! ...` spelling issue 14 lists decodes too, as [ErrorPrefix.BANGS] with a different
 *   message - two values, deliberately, because they are two different lines.
 * - **The thermal sentence names a heater, not a line number**, so [ErrorRs.lastLine] stays null
 *   on all of them: `Heater_ID: 0` is a colon and digits with no `Last Line` marker in front.
 * - **Cold extrusion, long extrusion and `Hotend too cold` are not errors on the wire at all.**
 *   All three go out through `SERIAL_ECHO_MSG` (`module/motion.cpp` 2060-2077, `feature/pause.cpp`
 *   177), so they arrive under `echo:` and no [ErrorRs] ever sees them - the one of the three that
 *   is in `marlin-replies.txt` confirms it. See [TheEchoChannelOnes].
 *
 * None of these can be provoked against MarlinSim, which is the other half of what issue 171 asked.
 * The simulator *is* reachable again - `Marlin bugfix-2.1.x (Nov 18 2026 11:55:10)`,
 * `MACHINE_TYPE:MarlinSim`, `Cap:THERMAL_PROTECTION:1`, `Cap:EMERGENCY_PARSER:1` on
 * `localhost:8099`, where issue 159 found nothing listening - but every line here is written from
 * inside `kill()` or `stop()`, and `minkill()` ends in `for (;;) hal.watchdog_refresh()`
 * (`MarlinCore.cpp` 1057). Measured on 2026-11-24: a target set through one connection was still
 * set after a reconnect, so this simulator does not reboot when a client disconnects and there is
 * nothing a host can send to clear a halt. An `M112` would therefore cost a manual restart and buy
 * one line, so these stay firmware-sourced rather than captured.
 */
class SafetyHaltRsTest {

    @Nested
    inner class TheThermalFamily {

        @ParameterizedTest
        @ValueSource(
            strings = [
                "Error:Thermal Runaway, system stopped! Heater_ID: E0",
                "Error:Thermal Runaway, system stopped! Heater_ID: bed",
                "Error:Heating failed, system stopped! Heater_ID: E0",
                "Error:Thermal Malfunction, system stopped! Heater_ID: bed",
                "Error:MAXTEMP triggered, system stopped! Heater_ID: E0",
                "Error:MINTEMP triggered, system stopped! Heater_ID: bed",
            ]
        )
        fun `every catalogue message decodes to its own text under the Error prefix`(line: String) {
            assertEquals(
                ErrorRs(ErrorPrefix.ERROR, line.substring("Error:".length)),
                ErrorRs.decode(line),
            )
        }

        @Test
        fun `the heater the halt names is kept verbatim, in either spelling`() {
            assertEquals(
                ErrorRs(ErrorPrefix.ERROR, "Thermal Runaway, system stopped! Heater_ID: E0"),
                ErrorRs.decode("Error:Thermal Runaway, system stopped! Heater_ID: E0"),
            )
            assertEquals(
                ErrorRs(ErrorPrefix.ERROR, "Thermal Runaway, system stopped! Heater_ID: 0"),
                ErrorRs.decode("Error:Thermal Runaway, system stopped! Heater_ID: 0"),
            )
        }

        @Test
        fun `an ERR_INCLUDE_TEMP build's trailing reading is part of the message`() {
            assertEquals(
                ErrorRs(
                    ErrorPrefix.ERROR,
                    "MAXTEMP triggered, system stopped! Heater_ID: E0 (temp: 361.74)",
                ),
                ErrorRs.decode("Error:MAXTEMP triggered, system stopped! Heater_ID: E0 (temp: 361.74)"),
            )
        }

        @Test
        fun `the redundancy mismatch is the same shape with a sentence for a name`() {
            val message = "Heater switched off. Temperature difference between temp sensors is " +
                    "too high !, system stopped! Heater_ID: E0"
            assertEquals(ErrorRs(ErrorPrefix.ERROR, message), ErrorRs.decode("Error:$message"))
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "Error:Thermal Runaway, system stopped! Heater_ID: 0",
                "Error:MINTEMP triggered, system stopped! Heater_ID: E0",
                "Error:Heating failed, system stopped! Heater_ID: bed",
            ]
        )
        fun `a heater id is not read as a line number`(line: String) {
            assertNull(ErrorRs.decode(line)?.lastLine, "read a line number out of: $line")
        }
    }

    @Nested
    inner class TheHalts {

        @Test
        fun `kill reports the one sentence it is given`() {
            assertEquals(
                ErrorRs(ErrorPrefix.ERROR, "Printer halted. kill() called!"),
                ErrorRs.decode("Error:Printer halted. kill() called!"),
            )
        }

        @Test
        fun `stop keeps the parenthetical bugfix 2 1 x added and reads the older spelling too`() {
            val current = "Printer stopped due to errors. Fix the error and use M999 to restart. " +
                    "(Temperature is reset. Set it after restarting)"
            assertEquals(ErrorRs(ErrorPrefix.ERROR, current), ErrorRs.decode("Error:$current"))

            val older = "Printer stopped due to errors. Fix the error and use M999 to restart."
            assertEquals(ErrorRs(ErrorPrefix.ERROR, older), ErrorRs.decode("Error:$older"))
        }

        @Test
        fun `the watchdog timeout decodes`() {
            assertEquals(
                ErrorRs(ErrorPrefix.ERROR, "Watchdog timeout. Reset required."),
                ErrorRs.decode("Error:Watchdog timeout. Reset required."),
            )
        }
    }

    @Nested
    inner class TheDoublePrefixedOnes {

        @ParameterizedTest
        @ValueSource(
            strings = [
                "Error:!! STOP called because of BLTouch error - restart with M999",
                "Error:!! STOP called because of unhomed error - restart with M999",
                "Error:!! KILL caused by too much inactive time - current command: G1 X10 E5",
                "Error:!! KILL caused by KILL button/pin",
            ]
        )
        fun `the bangs Marlin writes inside the text stay inside the message`(line: String) {
            val decoded = ErrorRs.decode(line)
            assertEquals(ErrorPrefix.ERROR, decoded?.prefix)
            assertEquals(line.substring("Error:".length), decoded?.message)
        }

        @Test
        fun `the bare spelling issue 14 lists is a different value, under the bangs prefix`() {
            assertEquals(
                ErrorRs(ErrorPrefix.BANGS, "STOP called because of BLTouch error - restart with M999"),
                ErrorRs.decode("!! STOP called because of BLTouch error - restart with M999"),
            )
            assertEquals(
                ErrorRs(ErrorPrefix.BANGS, "KILL caused by KILL button/pin"),
                ErrorRs.decode("!! KILL caused by KILL button/pin"),
            )
        }
    }

    @Nested
    inner class TheEchoChannelOnes {

        @ParameterizedTest
        @ValueSource(
            strings = [
                "echo: cold extrusion prevented",
                "echo: too long extrusion prevented",
                "echo:Hotend too cold",
            ]
        )
        fun `no error prefix, so no ErrorRs`(line: String) {
            assertNull(ErrorRs.decode(line), "decoded as an error: $line")
        }

        @Test
        fun `what claims them instead`() {
            assertEquals(ColdExtrusionPrevented, MarlinRsDecoder.decode("echo: cold extrusion prevented"))
            assertEquals(
                EchoMessage("too long extrusion prevented"),
                MarlinRsDecoder.decode("echo: too long extrusion prevented"),
            )
            assertEquals(EchoMessage("Hotend too cold"), MarlinRsDecoder.decode("echo:Hotend too cold"))
        }
    }

    @Nested
    inner class TheWholeCatalogue {

        private val halts = listOf(
            "Error:Thermal Runaway, system stopped! Heater_ID: E0",
            "Error:Thermal Runaway, system stopped! Heater_ID: 0",
            "Error:Thermal Runaway, system stopped! Heater_ID: bed",
            "Error:Thermal Runaway, system stopped! Heater_ID: E0 (temp: 255.00)",
            "Error:Heating failed, system stopped! Heater_ID: E0",
            "Error:Thermal Malfunction, system stopped! Heater_ID: bed",
            "Error:MAXTEMP triggered, system stopped! Heater_ID: E0",
            "Error:MINTEMP triggered, system stopped! Heater_ID: bed",
            "Error:Heater switched off. Temperature difference between temp sensors is too high !, " +
                    "system stopped! Heater_ID: E0",
            "Error:Printer halted. kill() called!",
            "Error:Printer stopped due to errors. Fix the error and use M999 to restart. " +
                    "(Temperature is reset. Set it after restarting)",
            "Error:Watchdog timeout. Reset required.",
            "Error:!! STOP called because of BLTouch error - restart with M999",
            "Error:!! KILL caused by too much inactive time - current command: G1 X10 E5",
            "!! STOP called because of unhomed error - restart with M999",
            "!! KILL caused by KILL button/pin",
        )

        @Test
        fun `the live registry answers every one of them with the same value as the decoder`() {
            for (line in halts) {
                val decoded = ErrorRs.decode(line)
                assertEquals(decoded, MarlinRsDecoder.decode(line)) { "registry disagrees on: $line" }
            }
        }

        @Test
        fun `exactly one decoder in the chain claims each`() {
            for (line in halts) {
                val claimants = MarlinRsDecoder.decoders.count { it.match(line) }
                assertEquals(1, claimants) { "$claimants decoders claim: $line" }
            }
        }

        @Test
        fun `every halt survives encode then decode unchanged`() {
            for (line in halts) {
                val decoded = ErrorRs.decode(line)
                assertEquals(decoded, ErrorRs.decode(decoded!!.encode())) {
                    "round trip failed for " + decoded.encode()
                }
                assertEquals(line, decoded.encode()) { "re-encoded differently: $line" }
            }
        }
    }
}
