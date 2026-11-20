package org.qw3rtrun.p3d.g.marlin.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.protocol.SimpleOkRs

/**
 * Cover for the `M115` capability fold: [CapabilitiesReportReader] turning the `Cap:` lines of one
 * answer into one [CapabilitiesReport].
 *
 * The shapes are the measured ones - `CapabilitiesInterleaveProbe` against MarlinSim on 2026-11-20
 * read `FIRMWARE_NAME:...`, 38 `Cap:` lines and `ok P15 B3` at one arrival offset, so what the tests
 * below model is exactly what the wire does: a run of capabilities and then a reply that is not one.
 *
 * The claims: the first capability opens a report and every later one extends it, anything else is
 * declined without disturbing what is open, [CapabilitiesReportReader.flush] answers only when
 * something was offered and resets afterwards, and the block's [CapabilitiesReport.encode] writes
 * the lines the firmware would have sent.
 */
class CapabilitiesReportReaderTest {

    private val eeprom = CapabilityRs("EEPROM", true)
    private val sdcard = CapabilityRs("SDCARD", true)
    private val autoreport = CapabilityRs("AUTOREPORT_TEMP", false)

    @Test
    fun `a capability opens a report, and a second one extends it`() {
        val reader = CapabilitiesReportReader()
        assertTrue(reader.offer(eeprom))
        assertTrue(reader.offer(sdcard))

        assertEquals(CapabilitiesReport(listOf(eeprom, sdcard)), reader.flush())
    }

    @Test
    fun `a reply that is not a capability is declined`() {
        val reader = CapabilitiesReportReader()
        assertFalse(reader.offer(SimpleOkRs))
        assertFalse(reader.offer(FirmwareInfoRs(mapOf("FIRMWARE_NAME" to "Marlin bugfix-2.1.x"))))
        assertFalse(reader.offer(null))
        assertNull(reader.flush())
    }

    @Test
    fun `a declined reply leaves an open report untouched`() {
        // What ends a report is the next reply that is not a CapabilityRs - offer answering false is
        // the caller's signal to flush, and it must not have consumed or dropped anything.
        val reader = CapabilitiesReportReader()
        reader.offer(eeprom)
        assertFalse(reader.offer(SimpleOkRs))
        assertFalse(reader.offer(EndstopStateRs("x_min", false)))

        assertEquals(CapabilitiesReport(listOf(eeprom)), reader.flush())
    }

    @Test
    fun `flush with nothing offered answers null`() {
        assertNull(CapabilitiesReportReader().flush())
    }

    @Test
    fun `flush answers every capability offered, in the order offered`() {
        val reader = CapabilitiesReportReader()
        reader.offer(eeprom)
        reader.offer(autoreport)
        reader.offer(sdcard)

        val report = reader.flush()!!
        assertEquals(listOf(eeprom, autoreport, sdcard), report.capabilities)
    }

    @Test
    fun `flush resets the reader, and the next answer starts clean`() {
        val reader = CapabilitiesReportReader()
        reader.offer(eeprom)
        assertEquals(CapabilitiesReport(listOf(eeprom)), reader.flush())
        assertNull(reader.flush())

        assertTrue(reader.offer(sdcard))
        assertEquals(CapabilitiesReport(listOf(sdcard)), reader.flush())
    }

    @Test
    fun `the report a reader hands out does not change when the reader is reused`() {
        val reader = CapabilitiesReportReader()
        reader.offer(eeprom)
        val first = reader.flush()!!
        reader.offer(sdcard)
        reader.flush()

        assertEquals(listOf(eeprom), first.capabilities)
    }

    @Test
    fun `encode writes the Cap lines the firmware would send`() {
        assertEquals(
            "Cap:EEPROM:1\nCap:SDCARD:1\nCap:AUTOREPORT_TEMP:0",
            CapabilitiesReport(listOf(eeprom, sdcard, autoreport)).encode(),
        )
        assertEquals("", CapabilitiesReport(emptyList()).encode())
    }

    @Test
    fun `encode round-trips line by line through the capability decoder`() {
        // CapabilitiesReport.encode() writes a block, so there is no GRsDecoder to round-trip it
        // through whole. Decoding each line back is the equivalent claim, and it is the one the fold
        // rests on: every line of the block is still a CapabilityRs.
        val report = CapabilitiesReport(listOf(eeprom, sdcard, autoreport))
        val back = CapabilitiesReportReader()
        for (line in report.encode().split("\n")) {
            assertTrue(back.offer(CapabilityRs.decode(line)), "not a capability line: $line")
        }
        assertEquals(report, back.flush())
    }

    @Test
    fun `the measured M115 sequence folds into one report`() {
        // CapabilitiesInterleaveProbe, 2026-11-20: the header, the Cap: lines and the ok arrive in
        // that order with nothing between them. The header is not folded in - it is FirmwareInfoRs,
        // already one event per answer - so the reader declines it and the ok that follows.
        val reader = CapabilitiesReportReader()
        assertFalse(reader.offer(FirmwareInfoRs(mapOf("FIRMWARE_NAME" to "Marlin bugfix-2.1.x"))))
        assertTrue(reader.offer(CapabilityRs.decodeParams("Cap:EEPROM:1")))
        assertTrue(reader.offer(CapabilityRs.decodeParams("Cap:SDCARD:1")))
        assertFalse(reader.offer(SimpleOkRs))

        assertEquals(CapabilitiesReport(listOf(eeprom, sdcard)), reader.flush())
        assertNull(reader.flush())
    }
}
