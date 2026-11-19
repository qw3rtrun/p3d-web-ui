package org.qw3rtrun.p3d.g.marlin.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.protocol.SimpleOkRs

/**
 * Cover for `M119`'s report as **one** value - the redo of #35, which first shipped the state lines
 * as an event each and was rejected.
 *
 * The lines are the capture `MultilineInterleaveProbe` took off MarlinSim bugfix-2.1.x on
 * localhost:8099 (2026-11-19) with `M155 S1` running: the header, three state lines and the `ok`,
 * all at the same millisecond, with the auto-report ticks landing before and after the burst and
 * never inside it. That is what makes "accumulate while the replies are members of this family"
 * sound here, and the claims below are all of it: the header opens, a state line extends, anything
 * else is refused and the refusal is the caller's cue to flush.
 */
class EndstopReportReaderTest {

    private val header = EndstopReportHeader
    private val xMin = EndstopStateRs("x_min", false)
    private val yMin = EndstopStateRs("y_min", false)
    private val zMin = EndstopStateRs("z_min", false)

    @Test
    fun `the captured burst folds into one report, in firmware order`() {
        val reader = EndstopReportReader()
        assertTrue(reader.offer(header))
        assertTrue(reader.offer(xMin))
        assertTrue(reader.offer(yMin))
        assertTrue(reader.offer(zMin))

        assertEquals(EndstopReport(listOf(xMin, yMin, zMin)), reader.flush())
    }

    @Test
    fun `offer never produces the report - flush does, and only once`() {
        val reader = EndstopReportReader()
        reader.offer(header)
        reader.offer(xMin)

        // flush() is the caller's only way to end a report, so "not yet" is the caller not having
        // called it - there is no line that ends an M119 answer the way `End file list` ends M20's.
        assertEquals(EndstopReport(listOf(xMin)), reader.flush())
        assertNull(reader.flush())
    }

    @Test
    fun `a reply from outside the family is refused, which is what ends a report`() {
        val reader = EndstopReportReader()
        reader.offer(header)
        reader.offer(xMin)

        assertFalse(reader.offer(SimpleOkRs))
        assertFalse(reader.offer(BareTemperatureRs()))
        assertEquals(EndstopReport(listOf(xMin)), reader.flush())
    }

    @Test
    fun `a non-member with nothing open is refused and there is nothing to flush`() {
        val reader = EndstopReportReader()
        assertFalse(reader.offer(SimpleOkRs))
        assertFalse(reader.offer(BareTemperatureRs()))
        assertFalse(reader.offer(null))
        assertFalse(reader.offer("x_min: open"))
        assertNull(reader.flush())
    }

    @Test
    fun `a second report carries nothing over from the first`() {
        val reader = EndstopReportReader()
        reader.offer(header)
        reader.offer(xMin)
        reader.offer(yMin)
        assertEquals(EndstopReport(listOf(xMin, yMin)), reader.flush())

        val triggered = EndstopStateRs("z_min", true)
        reader.offer(header)
        reader.offer(triggered)
        assertEquals(EndstopReport(listOf(triggered)), reader.flush())
    }

    @Test
    fun `state lines with no header still fold - the header contributes nothing but opening`() {
        // A reconnect mid-burst loses the header, and the states are still the answer. The header
        // is not carried in the value either: EndstopReport.encode() writes it back from the
        // literal, so there is nothing about it to remember.
        val reader = EndstopReportReader()
        assertTrue(reader.offer(xMin))
        assertEquals(EndstopReport(listOf(xMin)), reader.flush())
    }

    @Test
    fun `a header alone is an empty report rather than absence`() {
        // The same distinction SdFileList draws for an empty card: the machine answered, and it
        // named no endstops. That is a different fact from no answer at all, which is null.
        val reader = EndstopReportReader()
        reader.offer(header)
        assertEquals(EndstopReport(emptyList()), reader.flush())
    }

    @Test
    fun `the block round-trips through the decoder and the reader`() {
        // encode() writes several lines, so there is no GRsDecoder to round-trip it through -
        // the same position SdFileList is in. Feeding the lines back through EndstopRsDecoder and
        // the reader is the equivalent claim.
        for (report in listOf(
            EndstopReport(emptyList()),
            EndstopReport(listOf(xMin)),
            EndstopReport(listOf(xMin, yMin, EndstopStateRs("z_probe", true))),
        )) {
            val reader = EndstopReportReader()
            for (line in report.encode().split("\n")) {
                val reply = EndstopRsDecoder.decode(line)
                assertTrue(reply != null, "did not decode: $line")
                assertTrue(reader.offer(reply), "not folded: $line")
            }
            assertEquals(report, reader.flush()) { "round trip failed for: ${report.encode()}" }
        }
    }

    @Test
    fun `encode writes the block the firmware sent`() {
        assertEquals(
            "Reporting endstop status\nx_min: open\ny_min: open\nz_min: open",
            EndstopReport(listOf(xMin, yMin, zMin)).encode(),
        )
        assertEquals("Reporting endstop status", EndstopReport(emptyList()).encode())
    }
}
