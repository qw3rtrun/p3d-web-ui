package org.qw3rtrun.p3d.g.marlin.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.qw3rtrun.p3d.core.msg.OKReceivedEvent
import org.qw3rtrun.p3d.g.protocol.BaseRsDecoder
import org.qw3rtrun.p3d.g.protocol.BusyRs
import org.qw3rtrun.p3d.g.protocol.SimpleBusyRs
import org.qw3rtrun.p3d.g.protocol.GRs
import org.qw3rtrun.p3d.g.protocol.OkRs
import org.qw3rtrun.p3d.g.protocol.SimpleOkRs
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
                OkTemperatureRs(
                    hotend = HeaterReading(dec("210.00"), dec("210.00"), 127),
                    bed = HeaterReading(dec("60.00"), dec("60.00"), 80),
                ),
                TemperatureRsDecoder.decode("ok T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80"),
            )
        }

        @Test
        fun `a report without the ok prefix carries the same payload in the other class`() {
            val report = TemperatureRsDecoder.decode("T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80")
            assertEquals(false, report?.ok)
            assertEquals(HeaterReading(dec("210.00"), dec("210.00"), 127), report?.hotend)
            assertEquals(
                BareTemperatureRs(
                    hotend = HeaterReading(dec("210.00"), dec("210.00"), 127),
                    bed = HeaterReading(dec("60.00"), dec("60.00"), 80),
                ),
                report,
            )
        }

        @Test
        fun `only the ok-prefixed report is an acknowledgement`() {
            // The whole reason these are two classes. A host that frees a slot in its send window
            // per OKReceivedEvent must not free one for an M155 auto-report the printer sent
            // unbidden - it would run ahead of a queue slot the printer never granted.
            val acked = TemperatureRsDecoder.decode("ok T:210.00 /210.00 @:127")!!
            val unbidden = TemperatureRsDecoder.decode("T:210.00 /210.00 @:127")!!

            assertTrue(acked is OkRs<*>)
            assertTrue(acked is OKReceivedEvent)
            assertTrue(acked.ok)

            assertFalse(unbidden is OkRs<*>)
            assertFalse(unbidden is OKReceivedEvent)
            assertFalse(unbidden.ok)

            // Same payload, different type - that is the only difference between them.
            assertEquals(unbidden.hotend, acked.hotend)
        }

        @Test
        fun `each class decodes only its own flavour`() {
            assertNull(BareTemperatureRs.decode("ok T:210.00 /210.00"))
            assertNull(OkTemperatureRs.decode("T:210.00 /210.00"))
            assertNotNull(BareTemperatureRs.decode("T:210.00 /210.00"))
            assertNotNull(OkTemperatureRs.decode("ok T:210.00 /210.00"))
        }

        @Test
        fun `a machine with no heated bed still reports`() {
            // The decoder this replaces required both T and B, so it read nothing at all here.
            val report = TemperatureRsDecoder.decode("ok T:24.31 /0.00 @:0")
            assertEquals(HeaterReading(dec("24.31"), dec("0.00"), 0), report?.hotend)
            assertNull(report?.bed)
        }

        @Test
        fun `multiple hotends are reported alongside the active one`() {
            val report = TemperatureRsDecoder.decode(
                "T:24.31 /0.00 B:23.87 /0.00 T0:24.31 /0.00 T1:25.02 /0.00 @:0 B@:0 @0:0 @1:0"
            )
            assertEquals(2, report?.hotends?.size)
            assertEquals(HeaterReading(dec("25.02"), dec("0.00"), 0), report?.hotends?.get(1))
        }

        @Test
        fun `every sensor letter Marlin writes is read`() {
            val report = TemperatureRsDecoder.decode(
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
            val report = TemperatureRsDecoder.decode("T:93.2 B:22.9")
            assertEquals(HeaterReading(dec("93.2")), report?.hotend)
            assertEquals(HeaterReading(dec("22.9")), report?.bed)
        }

        @Test
        fun `a temperature below absolute zero decodes, which is how absence is reported`() {
            assertEquals(dec("-280.00"), TemperatureRsDecoder.decode("T:-280.00 /0.00")?.hotend?.current)
        }

        @Test
        fun `the core event view is filled in`() {
            val report = TemperatureRsDecoder.decode("ok T:210.00 /205.00 B:60.00 /55.00 @:127 B@:80")!!
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
                val report = TemperatureRsDecoder.decode(line)!!
                assertEquals(line, report.encode()) { "encode differs for: $line" }
                assertEquals(report, TemperatureRsDecoder.decode(report.encode()))
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
            assertNull(TemperatureRsDecoder.decode(line), "should not decode: $line")
        }

        @Test
        fun `a repeated field is malformed, not a silent overwrite`() {
            assertNull(TemperatureRsDecoder.decode("T:210.00 /210.00 T:220.00 /220.00"))
        }

        @Test
        fun `a power too wide for an Int yields absence and never throws`() {
            assertNull(TemperatureRsDecoder.decode("T:210.00 /210.00 @:99999999999"))
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
        fun `M27 C names the file the card is positioned on`() {
            // Captured from MarlinSim with an empty card: the firmware writes the words
            // "(no file)", and absence is modelled as null rather than that sentinel text.
            assertEquals(SdCurrentFile(null), SdStatusRsDecoder.decode("Current file: (no file)"))
            assertEquals(SdCurrentFile("jingle.gco"), SdStatusRsDecoder.decode("Current file: jingle.gco"))
            assertEquals("Current file: (no file)", SdCurrentFile(null).encode())
            assertEquals("Current file: jingle.gco", SdCurrentFile("jingle.gco").encode())
            assertNull(SdStatusRsDecoder.decode("Current file:"))
        }

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
        fun `Marlin's busy keepalive is split out from ordinary echo`() {
            // Captured from MarlinSim while homing: host_keepalive() goes out through
            // SERIAL_ECHO_MSG, so the notice arrives with the echo prefix and the canonical
            // BusyRs - which reads the bare `busy: processing` the RepRap protocol describes -
            // never fires. It used to land as undifferentiated EchoMessage text.
            assertEquals(BusyNotice("processing"), EchoRsDecoder.decode("echo:busy: processing"))
            assertEquals(BusyNotice("paused for user"), EchoRsDecoder.decode("echo:busy: paused for user"))
            // Three-way partition: nothing is claimed by two.
            assertNull(EchoMessage.decode("echo:busy: processing"))
            assertNull(UnknownCommand.decode("echo:busy: processing"))
            assertNull(BusyNotice.decode("echo:SD card ok"))
        }

        @Test
        fun `the canonical variant is left alone and still reads the bare line`() {
            // The two spellings stay apart: this one is the protocol's and other firmwares send it.
            assertEquals(SimpleBusyRs("processing"), SimpleBusyRs.decode("busy: processing"))
            assertNull(BusyNotice.decode("busy: processing"))
            assertNull(SimpleBusyRs.decode("echo:busy: processing"))
        }

        @Test
        fun `both spellings are one type to a host that only wants to know it is busy`() {
            // The point of the BusyRs interface: a host asks "busy, and why?" without knowing
            // which firmware it is talking to, or which channel carried the notice.
            val bare: GRs<*>? = BaseRsDecoder.decode("busy: processing")
            val marlin: GRs<*>? = MarlinRsDecoder.decode("echo:busy: processing")

            for (busy in listOf(bare, marlin)) {
                assertTrue(busy is BusyRs<*>) { "not a BusyRs: $busy" }
                assertEquals(BusyRs.PROCESSING, (busy as BusyRs<*>).reason)
            }

            // Marlin's is an echo line as well - the two views are of one value, not two.
            assertTrue(marlin is EchoRs<*>) { "not an EchoRs: $marlin" }
            assertEquals(BusyNotice(BusyRs.PROCESSING), marlin)
            assertEquals(SimpleBusyRs(BusyRs.PROCESSING), bare)
        }

        @Test
        fun `each variant round-trips to its own spelling, not the other's`() {
            assertEquals("busy: processing", SimpleBusyRs("processing").encode())
            assertEquals("echo:busy: processing", BusyNotice("processing").encode())
        }

        @ParameterizedTest
        @ValueSource(strings = ["echo:busy", "echo:busy:", "echo:busy:   ", "echo:busybody"])
        fun `a busy echo without a reason stays ordinary prose`(line: String) {
            assertNull(BusyNotice.decode(line), "should not decode: $line")
            assertNotNull(EchoRsDecoder.decode(line))
        }

        @Test
        fun `M111's debug level decodes, flags and all`() {
            // Captured from MarlinSim: S0, S8, S38 and S247 in that order.
            assertEquals(DebugLevelRs(emptySet()), EchoRsDecoder.decode("echo:DEBUG:off"))
            assertEquals(DebugLevelRs(setOf("DRYRUN")), EchoRsDecoder.decode("echo:DEBUG:DRYRUN"))
            assertEquals(DebugLevelRs(setOf("INFO", "ERRORS")),
                EchoRsDecoder.decode("echo:DEBUG:INFO,ERRORS"))
            assertEquals(
                DebugLevelRs(setOf("ECHO", "INFO", "ERRORS", "COMMUNICATION")),
                EchoRsDecoder.decode("echo:DEBUG:ECHO,INFO,ERRORS,COMMUNICATION")
            )
        }

        @Test
        fun `no debug flags is off, and round-trips as off`() {
            // `echo:DEBUG:` with nothing after it is not a report; `off` is how the firmware
            // spells no flags, and an empty set has to encode back to that word.
            assertEquals("echo:DEBUG:off", DebugLevelRs(emptySet()).encode())
            assertNull(DebugLevelRs.decode("echo:DEBUG:"))
            assertNull(DebugLevelRs.decode("echo:DEBUG:   "))
        }

        @Test
        fun `M302's cold extrusion report decodes both states and the threshold`() {
            // Captured from MarlinSim after M302 P0, M302 P1 and M302 S0.
            assertEquals(ColdExtrusionRs(false, 170),
                EchoRsDecoder.decode("echo:Cold extrudes are disabled (min temp 170C)"))
            assertEquals(ColdExtrusionRs(true, 170),
                EchoRsDecoder.decode("echo:Cold extrudes are enabled (min temp 170C)"))
            assertEquals(ColdExtrusionRs(true, 0),
                EchoRsDecoder.decode("echo:Cold extrudes are enabled (min temp 0C)"))
        }

        @Test
        fun `a refused cold extrude is its own notice`() {
            // The command was acknowledged with an ok and then did nothing - an echo a host must
            // not treat as prose, for the same reason as UnknownCommand.
            //
            // Note the blank after the colon, which the M302 report does not have: the two
            // messages reach the line through different calls in the firmware. The first live
            // sweep read this as prose because the decoder did not cross it.
            assertEquals(ColdExtrusionPrevented, EchoRsDecoder.decode("echo: cold extrusion prevented"))
            assertEquals(ColdExtrusionPrevented, EchoRsDecoder.decode("echo:cold extrusion prevented"))
            assertNull(EchoMessage.decode("echo: cold extrusion prevented"))
        }

        @Test
        fun `a command naming a tool the machine lacks is its own notice`() {
            // Captured from MarlinSim: M104 T5 S100 and M104 T1 S205 on a one-extruder machine.
            assertEquals(InvalidExtruder("M104", 5), EchoRsDecoder.decode("echo:M104 Invalid extruder 5"))
            assertEquals(InvalidExtruder("M104", 1), EchoRsDecoder.decode("echo:M104 Invalid extruder 1"))
            assertEquals("echo:M104 Invalid extruder 5", InvalidExtruder("M104", 5).encode())
            assertNull(EchoMessage.decode("echo:M104 Invalid extruder 5"))
        }

        @ParameterizedTest
        @ValueSource(strings = [
            "echo: Invalid extruder 5",
            "echo:M104 Invalid extruder",
            "echo:M104 Invalid extruder x",
            "echo:M104 Invalid extruder 5 maybe",
        ])
        fun `an invalid-extruder line that is malformed decodes to absence`(line: String) {
            assertNull(InvalidExtruder.decode(line), "should not decode: $line")
        }

        @ParameterizedTest
        @ValueSource(strings = [
            "echo:Cold extrudes are enabled",
            "echo:Cold extrudes are sideways (min temp 170C)",
            "echo:Cold extrudes are enabled (min temp C)",
            "echo:Cold extrudes are enabled (min temp 170C) and then some",
        ])
        fun `a cold extrusion line that is not the report decodes to absence`(line: String) {
            assertNull(ColdExtrusionRs.decode(line), "should not decode: $line")
        }

        @Test
        fun `the echo specialisations still partition the prefix`() {
            val claimed = listOf(
                "echo:Unknown command: \"M9999\"",
                "echo:M104 Invalid extruder 5",
                "echo: cold extrusion prevented",
                "echo:busy: processing",
                "echo:DEBUG:INFO,ERRORS",
                "echo:Cold extrudes are enabled (min temp 170C)",
                "echo:cold extrusion prevented",
            )
            for (line in claimed) {
                assertNull(EchoMessage.decode(line)) { "EchoMessage should not claim: $line" }
                assertNotNull(EchoRsDecoder.decode(line)) { "nothing claimed: $line" }
            }
            // and ordinary prose is still ordinary prose
            assertEquals(EchoMessage("Print time: 0s"), EchoRsDecoder.decode("echo:Print time: 0s"))
        }

        @Test
        fun `all of them round-trip`() {
            for (echo in listOf(EchoMessage("SD card ok"), UnknownCommand("M9999"),
                                BusyNotice("processing"), DebugLevelRs(emptySet()),
                                DebugLevelRs(setOf("INFO", "ERRORS")), ColdExtrusionRs(true, 170),
                                ColdExtrusionRs(false, 0), ColdExtrusionPrevented,
                                InvalidExtruder("M104", 5))) {
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
            "ok T:210.00 /210.00 B:60.00 /60.00 @:127 B@:80" to OkTemperatureRs(
                hotend = HeaterReading(BigDecimal("210.00"), BigDecimal("210.00"), 127),
                bed = HeaterReading(BigDecimal("60.00"), BigDecimal("60.00"), 80),
            ),
            "T:24.31 /0.00 @:0" to BareTemperatureRs(
                hotend = HeaterReading(BigDecimal("24.31"), BigDecimal("0.00"), 0),
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
