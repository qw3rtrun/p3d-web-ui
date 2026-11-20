package org.qw3rtrun.p3d.g.marlin.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.protocol.SimpleOkRs

/**
 * Cover for `M503`'s dump as **one** value - the settings half of the #35 redo.
 *
 * The fixture mirrors the head of the dump `SettingsRsTest` holds verbatim (MarlinSim bugfix-2.1.x):
 * a section heading, the commands under it, the next heading. `MultilineInterleaveProbe`
 * (localhost:8099, 2026-11-19, `M155 S1` running) measured the burst as contiguous - `ok`
 * immediately behind the last `echo:` line, auto-report ticks before or after it and never inside -
 * which is why folding stops at the first reply that is not a member rather than at `ok`.
 *
 * The three claims: the dump folds in firmware order, a lone read-back folds into a one-entry report
 * on the same path, and [EepromReport] is refused in both states the reader can be in.
 */
class SettingsReportReaderTest {

    private val linearUnits = SettingsSection("Linear Units")
    private val g21 = SettingsValue("G21", "G21 ; (mm)")
    private val stepsPerUnit = SettingsSection("Steps per unit")
    private val m92 = SettingsValue("M92", "M92 X80.00 Y80.00 Z400.00 E500.00")

    @Test
    fun `the dump folds into one report, in firmware order`() {
        val reader = SettingsReportReader()
        assertTrue(reader.offer(linearUnits))
        assertTrue(reader.offer(g21))
        assertTrue(reader.offer(stepsPerUnit))
        assertTrue(reader.offer(m92))

        assertEquals(
            SettingsReport(listOf(linearUnits, g21, stepsPerUnit, m92)),
            reader.flush(),
        )
    }

    @Test
    fun `a lone read-back folds into a one-entry report`() {
        // A bare `M92` answers in SettingsValue's unprefixed shape with no dump around it. It still
        // crosses as a SettingsReport: one wire shape for the family whatever its length, rather
        // than two shapes for "one value" depending on what else happened to arrive.
        val reader = SettingsReportReader()
        assertTrue(reader.offer(m92))
        assertEquals(SettingsReport(listOf(m92)), reader.flush())
    }

    @Test
    fun `offer never produces the report - flush does, and only once`() {
        val reader = SettingsReportReader()
        reader.offer(stepsPerUnit)
        reader.offer(m92)

        assertEquals(SettingsReport(listOf(stepsPerUnit, m92)), reader.flush())
        assertNull(reader.flush())
    }

    @Test
    fun `an EepromReport is refused on its own - it is M504's answer, not part of a dump`() {
        val reader = SettingsReportReader()
        assertFalse(reader.offer(EepromReport("OK")))
        assertNull(reader.flush())
    }

    @Test
    fun `an EepromReport is refused mid-dump and is not folded into the open batch`() {
        // Defensive: measured captures never put it inside a dump, and if one ever did it would end
        // the batch rather than join it, because it is a different command's answer.
        val reader = SettingsReportReader()
        reader.offer(stepsPerUnit)
        reader.offer(m92)

        assertFalse(reader.offer(EepromReport("OK")))
        assertEquals(SettingsReport(listOf(stepsPerUnit, m92)), reader.flush())
    }

    @Test
    fun `a reply from outside the family is refused, which is what ends a report`() {
        val reader = SettingsReportReader()
        reader.offer(m92)

        assertFalse(reader.offer(SimpleOkRs))
        assertFalse(reader.offer(BareTemperatureRs()))
        assertFalse(reader.offer(null))
        assertFalse(reader.offer("  M92 X80.00"))
        assertEquals(SettingsReport(listOf(m92)), reader.flush())
    }

    @Test
    fun `a second dump carries nothing over from the first`() {
        val reader = SettingsReportReader()
        reader.offer(stepsPerUnit)
        reader.offer(m92)
        assertEquals(SettingsReport(listOf(stepsPerUnit, m92)), reader.flush())

        reader.offer(linearUnits)
        reader.offer(g21)
        assertEquals(SettingsReport(listOf(linearUnits, g21)), reader.flush())
    }

    @Test
    fun `encode writes the entries line by line, as each entry encodes itself`() {
        // The round-trip claim is made per line rather than through a reader, as SdFileListTest does
        // for M20: SettingsRsDecoder reads one line to one entry, so the honest assertion is that
        // the block is exactly its entries' own encodings joined by newlines - and that each of
        // those lines decodes back to the entry it came from.
        val report = SettingsReport(listOf(linearUnits, g21, stepsPerUnit, m92))

        assertEquals(
            "echo:; Linear Units:\n" +
                "echo:  G21 ; (mm)\n" +
                "echo:; Steps per unit:\n" +
                "echo:  M92 X80.00 Y80.00 Z400.00 E500.00",
            report.encode(),
        )

        val lines = report.encode().split("\n")
        assertEquals(report.entries.size, lines.size)
        for (i in lines.indices) {
            assertEquals(report.entries[i].encode(), lines[i])
            assertEquals(report.entries[i], SettingsRsDecoder.decode(lines[i]))
        }
    }

    @Test
    fun `the lines decode and fold back into the same report`() {
        val report = SettingsReport(listOf(linearUnits, g21, stepsPerUnit, m92))

        val reader = SettingsReportReader()
        for (line in report.encode().split("\n")) {
            val reply = SettingsRsDecoder.decode(line)
            assertTrue(reply != null, "did not decode: $line")
            assertTrue(reader.offer(reply), "not folded: $line")
        }
        assertEquals(report, reader.flush())
    }
}
