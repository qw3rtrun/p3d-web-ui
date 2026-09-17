package org.qw3rtrun.p3d.g.marlin.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.g.code.dsl.GRs
import org.qw3rtrun.p3d.g.marlin.protocol.SimpleOkRs
import java.math.BigDecimal
import java.util.UUID

/**
 * Cover for the tier 1 Marlin reply events of todo 12.
 *
 * Four claims per event, the same ones [org.qw3rtrun.p3d.g.marlin.protocol.BaseRsTest] makes: the
 * shapes Marlin actually writes decode, the decoded value round-trips through `encode`, junk
 * decodes to absence rather than throwing, and no line is claimed by two decoders.
 *
 * The literals are taken from the firmware that writes them - `print_heater_state`,
 * `report_current_position`, `Endstops::report_states`, `M115.cpp`, `cardreader.cpp` - because
 * Marlin's G-code documentation covers only the request side of every command.
 */
class MarlinRsTest {

    private fun dec(s: String) = BigDecimal(s)

    @Nested
    inner class Temperature {

        @Test
        fun `the ordinary single-hotend report decodes`() {
            assertEquals(
                TemperatureRs(
                    hotend = HeaterReading(dec("210.00"), dec("210.00"), 127),
                    bed = HeaterReading(dec("60.00"), dec("60.00"), 80),
                    ok = true,
                ),
                TemperatureRs.decode("ok T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80"),
            )
        }

        @Test
        fun `a report without the ok prefix decodes the same way`() {
            val report = TemperatureRs.decode("T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80")
            assertEquals(false, report?.ok)
            assertEquals(HeaterReading(dec("210.00"), dec("210.00"), 127), report?.hotend)
        }

        @Test
        fun `a machine with no heated bed still reports`() {
            // The decoder this replaces required both T and B, so it read nothing at all here.
            val report = TemperatureRs.decode("ok T:24.31 /0.00 @:0")
            assertEquals(HeaterReading(dec("24.31"), dec("0.00"), 0), report?.hotend)
            assertNull(report?.bed)
        }

        @Test
        fun `multiple hotends are reported alongside the active one`() {
            val report = TemperatureRs.decode(
                "T:24.31 /0.00 B:23.87 /0.00 T0:24.31 /0.00 T1:25.02 /0.00 @:0 B@:0 @0:0 @1:0"
            )
            assertEquals(2, report?.hotends?.size)
            assertEquals(HeaterReading(dec("25.02"), dec("0.00"), 0), report?.hotends?.get(1))
        }

        @Test
        fun `every sensor letter Marlin writes is read`() {
            val report = TemperatureRs.decode(
                "T:200.00 /200.00 B:60.00 /60.00 C:40.00 /45.00 L:15.00 /10.00 " +
                    "P:23.50 /0.00 M:31.20 /0.00 R:199.80 /200.00 @:120 B@:70 C@:30"
            )
            assertEquals(dec("40.00"), report?.chamber?.current)
            assertEquals(30, report?.chamber?.power)
            assertEquals(dec("15.00"), report?.cooler?.current)
            assertEquals(dec("23.50"), report?.probe?.current)
            assertEquals(dec("31.20"), report?.board?.current)
            assertEquals(dec("199.80"), report?.redundant?.current)
        }

        @Test
        fun `a target-less report decodes, as the RepRap spec writes it`() {
            val report = TemperatureRs.decode("T:93.2 B:22.9")
            assertEquals(HeaterReading(dec("93.2")), report?.hotend)
            assertEquals(HeaterReading(dec("22.9")), report?.bed)
        }

        @Test
        fun `a temperature below absolute zero decodes, which is how absence is reported`() {
            assertEquals(dec("-280.00"), TemperatureRs.decode("T:-280.00 /0.00")?.hotend?.current)
        }

        @Test
        fun `the core event view is filled in`() {
            val report = TemperatureRs.decode("ok T:210.00 /205.00 B:60.00 /55.00 @:127 B@:80")!!
            assertEquals(210.0, report.hotend()!!.current())
            assertEquals(205.0, report.hotend()!!.target())
            assertEquals(127, report.hotend()!!.power())
            assertEquals(55.0, report.bed()!!.target())
        }

        @Test
        fun `every shape round-trips`() {
            val lines = listOf(
                "ok T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80",
                "T:24.31 /0.00 @:0",
                "T:24.31 /0.00 B:23.87 /0.00 T0:24.31 /0.00 T1:25.02 /0.00 @:0 B@:0 @0:0 @1:0",
                "T:93.2 B:22.9",
            )
            for (line in lines) {
                val report = TemperatureRs.decode(line)!!
                assertEquals(line, report.encode()) { "encode differs for: $line" }
                assertEquals(report, TemperatureRs.decode(report.encode()))
            }
        }

        // The scan is all-or-nothing, so a line that merely contains a `T:` is not a report.
        @ParameterizedTest
        @ValueSource(
            strings = [
                "//action:prompt_begin T:1", "echo:T:210", "ok", "wait",
                "X:0.00 Y:0.00 Z:0.00 E:0.00", "T:210.00 /210.00 nonsense",
                "T:", "T:210.00 /", "Cap:EEPROM:1", "", "   ",
            ]
        )
        fun `a line that is not a temperature report decodes to absence`(line: String) {
            assertNull(TemperatureRs.decode(line), "should not decode: $line")
        }

        @Test
        fun `a repeated field is malformed, not a silent overwrite`() {
            assertNull(TemperatureRs.decode("T:210.00 /210.00 T:220.00 /220.00"))
        }

        @Test
        fun `a power too wide for an Int yields absence and never throws`() {
            assertNull(TemperatureRs.decode("T:210.00 /210.00 @:99999999999"))
        }
    }

    @Nested
    inner class Position {

        @Test
        fun `M114's answer decodes into position and stepper counts`() {
            val report = PositionRs.decode("X:0.00 Y:0.00 Z:0.00 E:0.00 Count X:0 Y:0 Z:0")!!
            assertEquals(4, report.axes.size)
            assertEquals(dec("0.00"), report.axes['X'])
            assertEquals(3, report.counts.size)
            assertEquals(0, report.counts['Z'])
        }

        @Test
        fun `a real position with counts that disagree decodes both halves`() {
            val report = PositionRs.decode("X:10.50 Y:-3.25 Z:0.30 E:120.40 Count X:840 Y:-260 Z:120")!!
            assertEquals(dec("-3.25"), report.axes['Y'])
            assertEquals(-260, report.counts['Y'])
        }

        @Test
        fun `extra axes decode`() {
            val report = PositionRs.decode("X:0.00 Y:0.00 Z:0.00 I:1.00 J:2.00 E:0.00")!!
            assertEquals(dec("1.00"), report.axes['I'])
            assertTrue(report.counts.isEmpty())
        }

        @Test
        fun `it round-trips`() {
            val line = "X:0.00 Y:0.00 Z:0.00 E:0.00 Count X:0 Y:0 Z:0"
            assertEquals(line, PositionRs.decode(line)!!.encode())
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "T:93.2 B:22.9", "ok T:210.00 /210.00", "Count X:0 Y:0",
                "X:0.00 Y:0.00 nonsense", "echo:X:1", "", "   ",
            ]
        )
        fun `a line that is not a position report decodes to absence`(line: String) {
            assertNull(PositionRs.decode(line), "should not decode: $line")
        }
    }

    @Nested
    inner class Endstops {

        @Test
        fun `the M119 block decodes one line at a time`() {
            assertEquals(EndstopReportHeader, EndstopRsDecoder.decode("Reporting endstop status"))
            assertEquals(EndstopStateRs("x_min", false), EndstopRsDecoder.decode("x_min: open"))
            assertEquals(EndstopStateRs("y_min", true), EndstopRsDecoder.decode("y_min: TRIGGERED"))
            assertEquals(EndstopStateRs("z_probe", false), EndstopRsDecoder.decode("z_probe: open"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["x_min", "x_max", "x2_min", "y2_max", "z3_min", "z_probe", "probe_en", "filament"])
        fun `every endstop name Marlin writes decodes`(name: String) {
            assertEquals(EndstopStateRs(name, false), EndstopStateRs.decode("$name: open"))
        }

        @Test
        fun `both states round-trip`() {
            for (state in listOf(EndstopStateRs("z_min", true), EndstopStateRs("z_min", false))) {
                assertEquals(state, EndstopStateRs.decode(state.encode()))
            }
        }

        // `<word>: <word>` is far too common a shape on this wire to claim loosely.
        @ParameterizedTest
        @ValueSource(strings = ["busy: processing", "Cap:EEPROM:1", "x_min: maybe", "nonsense: open"])
        fun `a line that is not an endstop state decodes to absence`(line: String) {
            assertNull(EndstopStateRs.decode(line), "should not decode: $line")
        }
    }

    @Nested
    inner class SdStatus {

        @Test
        fun `M27's two answers decode`() {
            assertEquals(SdPrinting(1234, 56789), SdStatusRsDecoder.decode("SD printing byte 1234/56789"))
            assertEquals(SdNotPrinting, SdStatusRsDecoder.decode("Not SD printing"))
        }

        @Test
        fun `a file larger than Int decodes`() {
            assertEquals(SdPrinting(3_000_000_000L, 4_000_000_000L),
                SdPrinting.decode("SD printing byte 3000000000/4000000000"))
        }

        @Test
        fun `both round-trip`() {
            for (status in listOf(SdPrinting(1234, 56789), SdNotPrinting)) {
                assertEquals(status, SdStatusRsDecoder.decode(status.encode()))
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["SD printing byte 1234", "SD printing byte a/b", "Not SD", "", "   "])
        fun `a malformed SD status decodes to absence`(line: String) {
            assertNull(SdStatusRsDecoder.decode(line), "should not decode: $line")
        }
    }

    @Nested
    inner class FirmwareAndCapabilities {

        private val m115 = "FIRMWARE_NAME:Marlin 2.1.2 SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin " +
            "PROTOCOL_VERSION:1.0 MACHINE_TYPE:3D Printer EXTRUDER_COUNT:1 " +
            "UUID:cede2a2f-41a2-4748-9b12-c55c62f367ff"

        @Test
        fun `M115's first line decodes, values with spaces intact`() {
            val info = FirmwareInfoRs.decode(m115)!!
            assertEquals("Marlin 2.1.2", info.firmwareName())
            // The value runs to the next KEY:, so a machine type with a space survives.
            assertEquals("3D Printer", info.machineType())
            assertEquals("1.0", info.protocolVersion())
            assertEquals(1, info.extruderCount())
            assertEquals(UUID.fromString("cede2a2f-41a2-4748-9b12-c55c62f367ff"), info.uuid())
        }

        @Test
        fun `a missing or unreadable field reads as absent, not as a throw`() {
            val info = FirmwareInfoRs.decode("FIRMWARE_NAME:Marlin UUID:not-a-uuid")!!
            assertNull(info.uuid())
            assertNull(info.machineType())
            assertEquals(0, info.extruderCount())
        }

        @Test
        fun `it round-trips`() {
            assertEquals(m115, FirmwareInfoRs.decode(m115)!!.encode())
        }

        @ParameterizedTest
        @ValueSource(strings = ["Cap:EEPROM:1", "Cap:AUTOREPORT_TEMP:1", "Cap:AUTOREPORT_POS:1", "Cap: SDCARD:1"])
        fun `a capability line decodes as enabled`(line: String) {
            assertEquals(true, CapabilityRs.decode(line)?.enabled())
        }

        @Test
        fun `a disabled capability and the true-false spelling both decode`() {
            assertEquals(CapabilityRs("EEPROM", false), CapabilityRs.decode("Cap:EEPROM:0"))
            assertEquals(CapabilityRs("EEPROM", true), CapabilityRs.decode("Cap:EEPROM:true"))
        }

        @Test
        fun `a capability this code has never heard of still decodes`() {
            assertEquals(CapabilityRs("SOMETHING_NEW", true), CapabilityRs.decode("Cap:SOMETHING_NEW:1"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["Cap:EEPROM", "Cap::1", "PROTOCOL_VERSION:1.0", "echo:Cap:EEPROM:1"])
        fun `a line that is not one of these decodes to absence`(line: String) {
            assertNull(CapabilityRs.decode(line), "should not decode as cap: $line")
            assertNull(FirmwareInfoRs.decode(line), "should not decode as firmware info: $line")
        }
    }

    @Nested
    inner class Echo {

        @Test
        fun `an echo line decodes to its text`() {
            assertEquals(EchoMessage("SD card ok"), EchoRsDecoder.decode("echo:SD card ok"))
            assertEquals(EchoMessage("Settings Stored (632 bytes; crc 8907)"),
                EchoRsDecoder.decode("echo:Settings Stored (632 bytes; crc 8907)"))
        }

        @Test
        fun `an unknown command is split out from ordinary echo`() {
            assertEquals(UnknownCommand("M9999"), EchoRsDecoder.decode("echo:Unknown command: \"M9999\""))
            // The two partition the prefix, so nothing is claimed by both.
            assertNull(EchoMessage.decode("echo:Unknown command: \"M9999\""))
            assertNull(UnknownCommand.decode("echo:SD card ok"))
        }

        @Test
        fun `both round-trip`() {
            for (echo in listOf(EchoMessage("SD card ok"), UnknownCommand("M9999"))) {
                assertEquals(echo, EchoRsDecoder.decode(echo.encode()))
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["busy: processing", "// comment", "ok", ""])
        fun `a line without the prefix decodes to absence`(line: String) {
            assertNull(EchoRsDecoder.decode(line), "should not decode: $line")
        }
    }

    @Nested
    inner class TheWholeSet {

        private val examples: Map<String, GRs<*>> = mapOf(
            "ok T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80" to TemperatureRs(
                hotend = HeaterReading(BigDecimal("210.00"), BigDecimal("210.00"), 127),
                bed = HeaterReading(BigDecimal("60.00"), BigDecimal("60.00"), 80),
                ok = true,
            ),
            "X:0.00 Y:0.00 Z:0.00 E:0.00 Count X:0 Y:0 Z:0" to PositionRs(
                mapOf('X' to BigDecimal("0.00"), 'Y' to BigDecimal("0.00"),
                    'Z' to BigDecimal("0.00"), 'E' to BigDecimal("0.00")),
                mapOf('X' to 0, 'Y' to 0, 'Z' to 0),
            ),
            "Reporting endstop status" to EndstopReportHeader,
            "x_min: open" to EndstopStateRs("x_min", false),
            "SD printing byte 1234/56789" to SdPrinting(1234, 56789),
            "Not SD printing" to SdNotPrinting,
            "Cap:EEPROM:1" to CapabilityRs("EEPROM", true),
            "echo:SD card ok" to EchoMessage("SD card ok"),
            "echo:Unknown command: \"M9999\"" to UnknownCommand("M9999"),
            "ok" to SimpleOkRs,
        )

        @Test
        fun `the registry decodes every tier 1 reply and the base protocol below it`() {
            for ((line, expected) in examples) {
                assertEquals(expected, MarlinRsDecoder.decode(line)) { "failed for: $line" }
            }
        }

        @Test
        fun `no line is claimed by two decoders`() {
            for (line in examples.keys) {
                val claimants = MarlinRsDecoder.decoders.filter { it.match(line) }
                assertEquals(1, claimants.size) { "${claimants.size} decoders claim: $line" }
            }
        }

        @Test
        fun `every reply survives encode then decode through the registry`() {
            for (expected in examples.values) {
                assertEquals(expected, MarlinRsDecoder.decode(expected.encode())) {
                    "round trip failed for " + expected.encode()
                }
            }
        }

        // Tiers 2 to 4 are not implemented, so these are absence rather than a wrong answer.
        @ParameterizedTest
        @ValueSource(
            strings = [
                "PID Autotune start", "Bilinear Leveling Grid:", "Begin file list",
                "Marlin 2.1.2", "  Free Memory: 2048  PlannerBufferBytes: 1232", "", "   ",
            ]
        )
        fun `a reply outside tier 1 decodes to absence and never throws`(line: String) {
            assertNull(MarlinRsDecoder.decode(line), "should not decode: $line")
        }
    }
}
