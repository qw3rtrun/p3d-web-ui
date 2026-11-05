package org.qw3rtrun.p3d.g.marlin.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Cover for the `M503` settings report and `M504`'s EEPROM notice.
 *
 * Every literal here was captured off MarlinSim
 * `FIRMWARE_NAME:Marlin bugfix-2.1.x (Oct 29 2026 10:28:56) MACHINE_TYPE:MarlinSim` - the full
 * `M503` dump in both of the shapes it comes in, and `M504`'s answer. Nothing is invented: the
 * failure text of `M504` is not here because the simulator never wrote one.
 *
 * The claim the feature rests on is in [TheTwoShapes]: `M503 S0` and a bare single-setting query
 * answer with no `echo:` prefix and no section headers, and each of those lines has to decode to
 * the **same** [SettingsValue] as its prefixed twin.
 */
class SettingsRsTest {

    /** The 25 lines of a plain `M503`, in the order the firmware wrote them. */
    private val dump: List<Pair<String, SettingsRs<*>>> = listOf(
        "echo:; Linear Units:" to SettingsSection("Linear Units"),
        "echo:  G21 ; (mm)" to SettingsValue("G21", "G21 ; (mm)"),
        "echo:; Auto Temp Control:" to SettingsSection("Auto Temp Control"),
        "echo:  M104 S0 B250 F0.00" to SettingsValue("M104", "M104 S0 B250 F0.00"),
        "echo:; Temperature Units:" to SettingsSection("Temperature Units"),
        "echo:  M149 C ; Units in Celsius" to SettingsValue("M149", "M149 C ; Units in Celsius"),
        "echo:; Filament settings (Disabled):" to SettingsSection("Filament settings (Disabled)"),
        "echo:  M200 S0 D1.75" to SettingsValue("M200", "M200 S0 D1.75"),
        "echo:; Steps per unit:" to SettingsSection("Steps per unit"),
        "echo:  M92 X80.00 Y80.00 Z400.00 E500.00"
            to SettingsValue("M92", "M92 X80.00 Y80.00 Z400.00 E500.00"),
        "echo:; Max feedrates (units/s):" to SettingsSection("Max feedrates (units/s)"),
        "echo:  M203 X300.00 Y300.00 Z5.00 E25.00"
            to SettingsValue("M203", "M203 X300.00 Y300.00 Z5.00 E25.00"),
        "echo:; Max Acceleration (units/s2):" to SettingsSection("Max Acceleration (units/s2)"),
        "echo:  M201 X3000.00 Y3000.00 Z100.00 E10000.00"
            to SettingsValue("M201", "M201 X3000.00 Y3000.00 Z100.00 E10000.00"),
        "echo:; Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>):"
            to SettingsSection("Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>)"),
        "echo:  M204 P3000.00 R3000.00 T3000.00"
            to SettingsValue("M204", "M204 P3000.00 R3000.00 T3000.00"),
        "echo:; Advanced (B<min_segment_time_us> S<min_feedrate> T<min_travel_feedrate> J<junc_dev>):"
            to SettingsSection("Advanced (B<min_segment_time_us> S<min_feedrate> T<min_travel_feedrate> J<junc_dev>)"),
        "echo:  M205 B20000.00 S0.00 T0.00 J0.01"
            to SettingsValue("M205", "M205 B20000.00 S0.00 T0.00 J0.01"),
        "echo:; Home offset:" to SettingsSection("Home offset"),
        "echo:  M206 X0.00 Y0.00 Z0.00" to SettingsValue("M206", "M206 X0.00 Y0.00 Z0.00"),
        "echo:; Material heatup parameters:" to SettingsSection("Material heatup parameters"),
        "echo:  M145 S0 H180.00 B70.00 F0" to SettingsValue("M145", "M145 S0 H180.00 B70.00 F0"),
        "echo:  M145 S1 H240.00 B110.00 F0" to SettingsValue("M145", "M145 S1 H240.00 B110.00 F0"),
        "echo:; Hotend PID:" to SettingsSection("Hotend PID"),
        "echo:  M301 P22.20 I1.08 D114.00" to SettingsValue("M301", "M301 P22.20 I1.08 D114.00"),
    )

    @Nested
    inner class TheWholeDump {

        @Test
        fun `every line of M503 decodes to its type and its fields`() {
            assertEquals(25, dump.size) { "the capture is 25 lines" }
            for ((line, expected) in dump) {
                assertEquals(expected, SettingsRsDecoder.decode(line)) { "failed for: $line" }
            }
        }

        @Test
        fun `every line of M503 decodes the same through the whole registry`() {
            for ((line, expected) in dump) {
                assertEquals(expected, MarlinRsDecoder.decode(line)) { "failed for: $line" }
            }
        }

        @Test
        fun `every line of M503 round-trips through encode`() {
            for ((line, _) in dump) {
                assertEquals(line, SettingsRsDecoder.decode(line)!!.encode()) { "failed for: $line" }
            }
        }

        @Test
        fun `the awkward section title keeps its brackets and takes the last colon`() {
            val line = "echo:; Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>):"
            val section = SettingsSection.decode(line)
            assertEquals(
                SettingsSection("Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>)"),
                section,
            )
            assertEquals(line, section!!.encode())
        }

        @Test
        fun `a section without a trailing colon does not decode`() {
            assertNull(SettingsRsDecoder.decode("echo:; Steps per unit"))
        }
    }

    @Nested
    inner class TheTwoShapes {

        /** The 13 value lines of `M503 S0` - no `echo:`, no headers, the same two-space indent. */
        private val unprefixed: List<String> = dump
            .filter { it.second is SettingsValue }
            .map { it.first.substring("echo:".length) }

        @Test
        fun `the unprefixed M503 S0 form decodes to the same value as its prefixed twin`() {
            assertEquals(13, unprefixed.size) { "the S0 capture is 13 lines" }
            for ((line, expected) in dump) {
                if (expected !is SettingsValue) continue
                val bare = line.substring("echo:".length)
                assertEquals(expected, SettingsValue.decode(bare)) { "failed for: $bare" }
                assertEquals(SettingsValue.decode(line), SettingsValue.decode(bare)) {
                    "the two shapes disagree for: $bare"
                }
            }
        }

        @Test
        fun `the unprefixed form decodes the same through the whole registry`() {
            for (line in unprefixed) {
                assertTrue(MarlinRsDecoder.decode(line) is SettingsValue) { "failed for: $line" }
            }
        }

        @Test
        fun `an unprefixed line re-encodes to the canonical echo-prefixed form`() {
            assertEquals(
                "echo:  M92 X80.00 Y80.00 Z400.00 E500.00",
                SettingsValue.decode("  M92 X80.00 Y80.00 Z400.00 E500.00")!!.encode(),
            )
        }

        @Test
        fun `a bare single-setting query answers in the S0 shape and decodes`() {
            assertEquals(
                SettingsValue("M92", "M92 X81.00 Y80.00 Z400.00 E500.00"),
                SettingsRsDecoder.decode("  M92 X81.00 Y80.00 Z400.00 E500.00"),
            )
        }

        @Test
        fun `a trailing comment stays in the command verbatim`() {
            assertEquals(
                SettingsValue("G21", "G21 ; (mm)"),
                SettingsRsDecoder.decode("  G21 ; (mm)"),
            )
        }
    }

    @Nested
    inner class Eeprom {

        @Test
        fun `M504 answers that the EEPROM is readable`() {
            assertEquals(EepromReport("OK"), SettingsRsDecoder.decode("echo:EEPROM OK"))
        }

        @Test
        fun `the EEPROM notice decodes through the whole registry`() {
            assertEquals(EepromReport("OK"), MarlinRsDecoder.decode("echo:EEPROM OK"))
        }

        @Test
        fun `the EEPROM notice round-trips`() {
            assertEquals("echo:EEPROM OK", EepromReport("OK").encode())
            assertEquals(EepromReport("OK"), SettingsRsDecoder.decode(EepromReport("OK").encode()))
        }

        @Test
        fun `an EEPROM notice with no message does not decode`() {
            assertNull(SettingsRsDecoder.decode("echo:EEPROM"))
            assertNull(SettingsRsDecoder.decode("echo:EEPROM "))
        }
    }

    @Nested
    inner class NotASettingsLine {

        @Test
        fun `an unindented unprefixed command is not a settings reply`() {
            assertNull(SettingsRsDecoder.decode("M92 X80"))
        }

        @Test
        fun `an M20 listing entry is not a settings reply`() {
            assertNull(SettingsRsDecoder.decode("TEST.GCO 47"))
        }

        @Test
        fun `an indented M20 listing entry is still not a settings reply`() {
            assertNull(SettingsRsDecoder.decode("  TEST.GCO 47"))
        }

        @Test
        fun `a filename that begins like a command head is not a settings reply`() {
            assertNull(SettingsRsDecoder.decode("G1.GCO 47"))
        }

        @Test
        fun `an indented filename that begins like a command head is not a settings reply`() {
            assertNull(SettingsRsDecoder.decode("  G1.GCO 47"))
        }

        @Test
        fun `an unknown command notice stays an unknown command notice`() {
            assertEquals(UnknownCommand("M503"), MarlinRsDecoder.decode("echo:Unknown command: \"M503\""))
            assertNull(SettingsRsDecoder.decode("echo:Unknown command: \"M503\""))
        }

        @Test
        fun `an invalid extruder notice stays an invalid extruder notice`() {
            assertEquals(InvalidExtruder("M104", 5), MarlinRsDecoder.decode("echo:M104 Invalid extruder 5"))
            assertNull(SettingsRsDecoder.decode("echo:M104 Invalid extruder 5"))
        }

        @Test
        fun `the busy keepalive stays a busy notice`() {
            assertEquals(BusyNotice("processing"), MarlinRsDecoder.decode("echo:busy: processing"))
            assertNull(SettingsRsDecoder.decode("echo:busy: processing"))
        }

        @Test
        fun `an acknowledgement is not a settings reply`() {
            assertNull(SettingsRsDecoder.decode("ok P15 B3"))
        }

        @Test
        fun `a position report is not a settings reply`() {
            assertNull(SettingsRsDecoder.decode("X:0.00 Y:0.00 Z:0.00 Count X:0"))
        }

        @ParameterizedTest
        @ValueSource(strings = [
            "", "   ", "echo:", "echo:  ", "echo:;", "echo:; :", "echo:  M", "echo:  M.",
            "  Free Memory: 2048  PlannerBufferBytes: 1232", " T:24.31 /0.00 @:0",
        ])
        fun `junk decodes to absence rather than throwing`(line: String) {
            assertNull(SettingsRsDecoder.decode(line)) { "should not decode: $line" }
        }
    }

    @Nested
    inner class ThePrefixPartition {

        @Test
        fun `EchoMessage no longer claims a settings line`() {
            for ((line, _) in dump) {
                assertNull(EchoMessage.decode(line)) { "EchoMessage should not claim: $line" }
            }
            assertNull(EchoMessage.decode("echo:EEPROM OK"))
        }

        @Test
        fun `ordinary echo prose is still ordinary echo prose`() {
            assertEquals(EchoMessage("Print time: 0s"), MarlinRsDecoder.decode("echo:Print time: 0s"))
        }

        @Test
        fun `no settings line is claimed by two registered decoders`() {
            val lines = dump.map { it.first } + "echo:EEPROM OK" +
                "  M92 X80.00 Y80.00 Z400.00 E500.00"
            for (line in lines) {
                val claimants = MarlinRsDecoder.decoders.filter { it.match(line) }
                assertEquals(1, claimants.size) { "${claimants.size} decoders claim: $line" }
            }
        }
    }
}
