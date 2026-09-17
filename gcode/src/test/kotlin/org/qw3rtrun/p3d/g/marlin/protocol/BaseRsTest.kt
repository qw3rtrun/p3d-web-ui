package org.qw3rtrun.p3d.g.marlin.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.g.protocol.ActionRs
import org.qw3rtrun.p3d.g.protocol.AdvancedOkRs
import org.qw3rtrun.p3d.g.protocol.BaseRsDecoder
import org.qw3rtrun.p3d.g.protocol.BusyRs
import org.qw3rtrun.p3d.g.protocol.CommentRsDecoder
import org.qw3rtrun.p3d.g.protocol.DebugRs
import org.qw3rtrun.p3d.g.protocol.ErrorPrefix
import org.qw3rtrun.p3d.g.protocol.ErrorRs
import org.qw3rtrun.p3d.g.protocol.ResendRs
import org.qw3rtrun.p3d.g.protocol.SimpleOkRs
import org.qw3rtrun.p3d.g.protocol.StartRs
import org.qw3rtrun.p3d.g.protocol.WaitRs

/**
 * Cover for the RepRap base protocol replies other than `ok`, which [OkDecoderTest] already pins.
 *
 * Every literal in here is a line the spec itself writes, including the whole worked resend
 * transcript. The claims are the same four for each reply: the documented spellings decode, the
 * decoded value round-trips through [encode], junk decodes to absence rather than throwing, and
 * no line is claimed by two decoders.
 *
 * @see <a href="https://reprap.org/wiki/G-code#Replies_from_the_RepRap_machine_to_the_host_computer">RepRap G-code, replies</a>
 */
class BaseRsTest {

    @Nested
    inner class Resend {

        // All four spellings the spec gives, plus the separator-free variants firmwares send.
        @ParameterizedTest
        @ValueSource(strings = ["Resend: 123", "Resend: N123", "rs:123", "rs 123", "RS N123", "resend:n123"])
        fun `every documented spelling decodes to the same request`(line: String) {
            assertEquals(ResendRs(123), ResendRs.decode(line))
        }

        @Test
        fun `a trailing newline is tolerated`() {
            assertEquals(ResendRs(66556), ResendRs.decode("Resend: 66556\n"))
        }

        @Test
        fun `encode normalises to the spelling the spec's own example uses`() {
            assertEquals("Resend: 66556", ResendRs(66556).encode())
            assertEquals(ResendRs(66556), ResendRs.decode(ResendRs(66556).encode()))
        }

        @Test
        fun `a line number at Int MAX_VALUE still decodes`() {
            assertEquals(ResendRs(Int.MAX_VALUE), ResendRs.decode("Resend: 2147483647"))
        }

        // spec 9: malformed replies are the normal case on a serial link, so each of these must
        // come back as absence rather than an exception out of the decoder.
        @ParameterizedTest
        @ValueSource(strings = ["Resend: 2147483648", "Resend: 9999999999", "Resend:", "Resend: N", "Resend: -1", "Resend: 12a", "resending: 12"])
        fun `malformed request yields absence and never throws`(line: String) {
            assertNull(ResendRs.decode(line), "should not decode: $line")
        }
    }

    @Nested
    inner class Errors {

        @Test
        fun `the spec's own error line decodes with its last line number`() {
            val error = ErrorRs.decode("Error:checksum mismatch, Last Line: 66555")
            assertEquals(ErrorRs(ErrorPrefix.ERROR, "checksum mismatch, Last Line: 66555"), error)
            assertEquals(66555, error?.lastLine)
        }

        @ParameterizedTest
        @ValueSource(strings = ["!! hardware fault", "Error:hardware fault", "fatal: hardware fault"])
        fun `all three prefixes decode`(line: String) {
            assertEquals("hardware fault", ErrorRs.decode(line)?.message)
        }

        @Test
        fun `each prefix keeps its own lexeme through a round trip`() {
            for (prefix in ErrorPrefix.entries) {
                val original = ErrorRs(prefix, "checksum mismatch, Last Line: 41")
                assertEquals(original, ErrorRs.decode(original.encode())) {
                    "round trip failed for " + original.encode()
                }
                assertTrue(original.encode().startsWith(prefix.lexeme))
            }
        }

        @Test
        fun `a message that names no line has no last line`() {
            assertNull(ErrorRs.decode("Error:Wrong checksum")?.lastLine)
            // Two numbers and no marker saying which is which, so it is deliberately not read.
            assertNull(ErrorRs.decode("Error:expected line 41 got 43")?.lastLine)
        }

        @Test
        fun `a last line number too wide for an Int reads as absent, not as a throw`() {
            assertEquals(null, ErrorRs.decode("Error:checksum mismatch, Last Line: 9999999999")?.lastLine)
        }

        @Test
        fun `an empty message round-trips under every prefix`() {
            for (prefix in ErrorPrefix.entries) {
                val original = ErrorRs(prefix, "")
                assertEquals(original, ErrorRs.decode(original.encode()))
            }
        }
    }

    @Nested
    inner class Busy {

        @ParameterizedTest
        @ValueSource(strings = ["busy: processing", "busy:processing", "BUSY: processing", "busy: processing\n"])
        fun `the documented spellings decode to the same reason`(line: String) {
            assertEquals(BusyRs(BusyRs.PROCESSING), BusyRs.decode(line))
        }

        @Test
        fun `a multi-word reason keeps its spaces`() {
            assertEquals(BusyRs("paused for user"), BusyRs.decode("busy: paused for user"))
            assertEquals(BusyRs.PAUSED_FOR_USER, BusyRs.decode("busy: paused for user")?.reason)
        }

        @Test
        fun `a reason this code has never seen still decodes`() {
            // The spec says "possible reasons are", not "the reasons are". An unknown reason must
            // not turn the line into one that does not decode at all.
            assertEquals(BusyRs("waiting for probe"), BusyRs.decode("busy: waiting for probe"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["busy:", "busy: ", "busy", "busybody: x"])
        fun `a busy line with no reason does not decode`(line: String) {
            assertNull(BusyRs.decode(line), "should not decode: $line")
        }

        @Test
        fun `every documented reason round-trips`() {
            for (reason in listOf(BusyRs.PROCESSING, BusyRs.PAUSED_FOR_USER, BusyRs.PAUSED_FOR_INPUT)) {
                assertEquals(BusyRs(reason), BusyRs.decode(BusyRs(reason).encode()))
            }
        }
    }

    @Nested
    inner class WaitAndStart {

        @ParameterizedTest
        @ValueSource(strings = ["wait", "wait\n", "WAIT", "  wait  "])
        fun `wait decodes`(line: String) {
            assertEquals(WaitRs, WaitRs.decode(line))
        }

        @ParameterizedTest
        @ValueSource(strings = ["start", "start\n", "START"])
        fun `start decodes`(line: String) {
            assertEquals(StartRs, StartRs.decode(line))
        }

        @ParameterizedTest
        @ValueSource(strings = ["waiting", "wait for it", "restart", "start now"])
        fun `a longer word is not the bare reply`(line: String) {
            assertNull(WaitRs.decode(line), "should not decode as wait: $line")
            assertNull(StartRs.decode(line), "should not decode as start: $line")
        }

        @Test
        fun `both round-trip`() {
            assertEquals(WaitRs, WaitRs.decode(WaitRs.encode()))
            assertEquals(StartRs, StartRs.decode(StartRs.encode()))
        }
    }

    @Nested
    inner class Comments {

        @Test
        fun `the spec's own debug line decodes to its text`() {
            val line = "// This is some debugging or other information on a line on its own. " +
                "It may be sent at any time. "
            assertEquals(
                DebugRs(
                    "This is some debugging or other information on a line on its own. " +
                            "It may be sent at any time."
                ),
                DebugRs.decode(line),
            )
        }

        @Test
        fun `an empty comment round-trips`() {
            assertEquals(DebugRs(""), DebugRs.decode("//"))
            assertEquals("//", DebugRs("").encode())
        }

        @ParameterizedTest
        @ValueSource(strings = ["//action:pause", "// action : pause", "//ACTION:pause"])
        fun `an action command decodes without an argument`(line: String) {
            assertEquals(ActionRs("pause"), ActionRs.decode(line))
        }

        @Test
        fun `an action command keeps its rest-of-line argument`() {
            assertEquals(ActionRs("out_of_filament", "T0"), ActionRs.decode("//action:out_of_filament T0"))
            assertEquals(
                ActionRs("prompt_begin", "Continue with the next layer?"),
                ActionRs.decode("//action:prompt_begin Continue with the next layer?"),
            )
        }

        @Test
        fun `an action is not also a debug line`() {
            // The two partition the `//` prefix, so nothing is claimed by both.
            assertNull(DebugRs.decode("//action:pause"))
            assertNull(ActionRs.decode("// just talking"))
            assertEquals(ActionRs("pause"), CommentRsDecoder.decode("//action:pause"))
            assertEquals(DebugRs("just talking"), CommentRsDecoder.decode("// just talking"))
        }

        @Test
        fun `an action this code does not know still decodes`() {
            assertEquals(ActionRs("something_new"), ActionRs.decode("//action:something_new"))
        }

        @Test
        fun `every documented action round-trips`() {
            val actions = listOf(
                ActionRs("start"), ActionRs("pause"), ActionRs("resume"), ActionRs("disconnect"),
                ActionRs("cancel"), ActionRs("out_of_filament", "T1"), ActionRs("paused"),
                ActionRs("resumed"), ActionRs("probe_rewipe"), ActionRs("probe_failed"),
                ActionRs("sd_inserted"), ActionRs("sd_ejected"), ActionRs("sd_updated"),
                ActionRs("prompt_begin", "Are you sure?"), ActionRs("prompt_choice", "Yes"),
                ActionRs("prompt_button", "No"), ActionRs("prompt_show"), ActionRs("prompt_end"),
            )
            for (action in actions) {
                assertEquals(action, ActionRs.decode(action.encode())) {
                    "round trip failed for " + action.encode()
                }
            }
        }
    }

    @Nested
    inner class TheWholeSet {

        // Exactly the lines the spec writes out, one per reply kind.
        private val examples = mapOf(
            "ok" to SimpleOkRs,
            "ok P15 B3" to AdvancedOkRs(15, 3),
            "Resend: 66556" to ResendRs(66556),
            "rs:123" to ResendRs(123),
            "Error:checksum mismatch, Last Line: 66555" to
                    ErrorRs(ErrorPrefix.ERROR, "checksum mismatch, Last Line: 66555"),
            "!! hardware fault" to ErrorRs(ErrorPrefix.BANGS, "hardware fault"),
            "wait" to WaitRs,
            "busy: processing" to BusyRs("processing"),
            "busy: paused for user" to BusyRs("paused for user"),
            "start" to StartRs,
            "//action:pause" to ActionRs("pause"),
            "// some debugging" to DebugRs("some debugging"),
        )

        @Test
        fun `the registry decodes every documented reply`() {
            for ((line, expected) in examples) {
                assertEquals(expected, BaseRsDecoder.decode(line)) { "failed for: $line" }
            }
        }

        @Test
        fun `no line is claimed by two decoders`() {
            for (line in examples.keys) {
                val claimants = BaseRsDecoder.decoders.count { it.match(line) }
                assertEquals(1, claimants) { "$claimants decoders claim: $line" }
            }
        }

        @Test
        fun `every reply survives encode then decode through the registry`() {
            for (expected in examples.values) {
                assertEquals(expected, BaseRsDecoder.decode(expected.encode())) {
                    "round trip failed for " + expected.encode()
                }
            }
        }

        // The spec's worked resend transcript, machine-to-host lines only, in order.
        @Test
        fun `the spec's communication-error transcript decodes line by line`() {
            assertEquals(SimpleOkRs, BaseRsDecoder.decode("ok"))
            val error = BaseRsDecoder.decode("Error:checksum mismatch, Last Line: 66555")
            assertEquals(66555, (error as ErrorRs).lastLine)
            assertEquals(ResendRs(66556), BaseRsDecoder.decode("Resend: 66556"))
            assertEquals(SimpleOkRs, BaseRsDecoder.decode("ok"))
        }

        // The spec calls the "every line carries a two-character prefix" rule obsolete, so an
        // unprefixed reply is normal and belongs to another decoder, not to this set.
        @ParameterizedTest
        @ValueSource(strings = ["T:93.2 B:22.9", "FIRMWARE_NAME:Marlin", "Cap:EEPROM:1", "", "   "])
        fun `a reply outside the base set decodes to absence and never throws`(line: String) {
            assertNull(BaseRsDecoder.decode(line), "should not decode: $line")
        }

        @Test
        fun `a reply that is not one of these is absence, not an exception`() {
            assertNull(BaseRsDecoder.decode("echo:busy processing"))
            assertNotNull(BaseRsDecoder.decode("busy: processing"))
        }
    }
}
