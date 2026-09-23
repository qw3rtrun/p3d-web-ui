package org.qw3rtrun.p3d.g.code.core.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.token.GLine
import org.qw3rtrun.p3d.g.code.core.token.GLiner
import org.qw3rtrun.p3d.g.code.core.token.GTokenizer

/**
 * The session layer: GCODE_spec.md section 7.2 (continuity and `M110`) and section 8.5 (what a host
 * answers a bad line with).
 *
 * This is the first stateful piece of the module - everything below it is a pure function from bytes
 * to tokens to lines - so most of these tests are about a *sequence*, not a line.
 */
class GCodeReaderTest {


    private fun lines(gcode: String): List<GLine> =
        GTokenizer.lines(gcode).toList()

    private fun line(gcode: String): GLine = lines(gcode).single()

    /** Reads [gcode] line by line and returns what the reader concluded about each. */
    private fun readAll(reader: GCodeReader, gcode: String): List<GReceipt> =
        lines(gcode).map { reader.read(it) }

    @Nested
    inner class Continuity {

        @Test
        fun `a clean ascending sequence is accepted`() {
            val reader = GCodeReader()

            val receipts = readAll(reader, "N1 G28*18\nN2 G28*17\nN3 T0*57\n")

            assertEquals(listOf(1, 2, 3), receipts.map { (it as GAccepted).number })
            assertEquals(3, reader.lastLine)
        }

        @Test
        fun `the counter starts at zero so the first line is one`() {
            // Marlin initialises last_N to 0, so a session's first transmitted line is N1 - which is
            // also why a session conventionally opens with `M110 N0` to make that explicit.
            assertEquals(0, GCodeReader().lastLine)
            assertInstanceOf(GAccepted::class.java, GCodeReader().read(line("N1 G28*18")))
        }

        @Test
        fun `a gap is rejected and names the last good line`() {
            val reader = GCodeReader()
            reader.read(line("N1 G28*18"))

            val rejected = reader.read(line("N3 T0*57")) as GOutOfSequence

            assertEquals(3, rejected.number)
            assertEquals(1, rejected.lastLine)
            assertEquals("Error:Line Number is not Last Line Number+1, Last Line: 1", rejected.msg)
            assertEquals(2, rejected.resend)
        }

        @Test
        fun `a rejected line does not move the counter`() {
            val reader = GCodeReader()
            reader.read(line("N1 G28*18"))
            reader.read(line("N3 T0*57"))

            assertEquals(1, reader.lastLine)
            // ...so the host's resend of N2 is then accepted normally.
            assertInstanceOf(GAccepted::class.java, reader.read(line("N2 G28*17")))
            assertEquals(2, reader.lastLine)
        }

        @Test
        fun `a repeat of the last line is discarded without an error`() {
            // spec 7.2, and the reason is the resend protocol's own race: the host had already
            // retransmitted when the Resend arrived. Answering with another resend would not
            // converge, so the line is dropped silently.
            val reader = GCodeReader()
            reader.read(line("N1 G28*18"))
            reader.read(line("N2 G28*17"))

            val duplicate = reader.read(line("N2 G28*17")) as GDuplicate

            assertEquals(2, duplicate.number)
            assertEquals(2, duplicate.lastLine)
            assertEquals(2, reader.lastLine)
        }

        @Test
        fun `the line before last is discarded too`() {
            // Marlin's window is WITHIN(gcode_N, last_N - 1, last_N) - two wide, not one.
            val reader = GCodeReader()
            readAll(reader, "N1 G28*18\nN2 G28*17\n")

            assertInstanceOf(GDuplicate::class.java, reader.read(line("N1 G28*18")))
            assertEquals(2, reader.lastLine)
        }

        @Test
        fun `anything further behind is a real error`() {
            // The tolerance is a window, not an open door below the counter.
            val reader = GCodeReader()
            readAll(reader, "N1 G28*18\nN2 G28*17\nN3 T0*57\n")

            assertInstanceOf(GOutOfSequence::class.java, reader.read(line("N1 G28*18")))
        }
    }

    @Nested
    inner class M110 {

        @Test
        fun `M110 sets the counter to its argument and not to its own line number`() {
            // spec 7.2 and Marlin's queue.cpp: for an M110 line the *second* N is taken as the new
            // counter value. `N1 M110 N7` therefore leaves the counter at 7, not at 1.
            val reader = GCodeReader()

            reader.read(line("N1 M110 N7*123"))

            assertEquals(7, reader.lastLine)
        }

        @Test
        fun `the line after a reset continues from the new value`() {
            val reader = GCodeReader()
            reader.read(line("N1 M110 N7*123"))

            assertInstanceOf(GAccepted::class.java, reader.read(line("N8 G28*27")))
            assertEquals(8, reader.lastLine)
        }

        @Test
        fun `M110 is exempt from the continuity check`() {
            // It has to be: resynchronising is the one thing a host does *because* the sequence is
            // already broken, so a reset that had to arrive in sequence would be useless.
            val reader = GCodeReader()
            readAll(reader, "N1 G28*18\n")

            assertInstanceOf(GAccepted::class.java, reader.read(line("N99 M110 N0*77")))
            assertEquals(0, reader.lastLine)
        }

        @Test
        fun `M110 without an argument sets the counter to its own line number`() {
            // Marlin falls back to the first N when there is no second one.
            val reader = GCodeReader()

            reader.read(line("N42 M110*21"))

            assertEquals(42, reader.lastLine)
        }

        @Test
        fun `M110 N0 opens a session`() {
            val reader = GCodeReader()

            val receipts = readAll(reader, "N0 M110 N0*125\nN1 G28*18\nN2 G28*17\n")

            assertTrue(receipts.all { it is GAccepted }) { "not all accepted: $receipts" }
            assertEquals(2, reader.lastLine)
        }

        @Test
        fun `M110 inside a comment is not an M110`() {
            // Marlin decides a line is an M110 with `strstr(command, "M110")` over the raw buffer,
            // which does not know where the comment starts. On this line it would find the text,
            // skip the continuity check, take the `N0` in the comment as the second N, and reset the
            // counter to 0 - all from a comment. Reading the parsed commands instead is a deliberate
            // divergence: that behaviour follows from scanning text rather than from any rule in
            // spec 7.2, and a comment must not be able to resynchronise a session.
            val reader = GCodeReader()
            readAll(reader, "N1 G28*18\n")

            val receipt = reader.read(line("N2 G28*17 ; M110 N0"))

            assertInstanceOf(GAccepted::class.java, receipt)
            assertEquals(2, reader.lastLine)
        }

        @Test
        fun `an M110 inside an M117 message does not reset`() {
            // This used to assert the opposite, as characterisation: `M117`'s argument is a
            // bare rest-of-line string (spec 3.4a), and while the module could not read one,
            // `M117 M110 N0` decomposed into two commands of which the second really was an
            // `M110 N0` - so a status message resynchronised the session. Marlin does the same
            // for its own worse reason (`strstr(command, "M110")` over the raw buffer).
            //
            // The reset value is now read off the line's *head*, and that head is `M117`: the
            // rest of the line is its message, whatever the message spells. The old test asked
            // to be flipped once this became readable, and this is that flip.
            val reader = GCodeReader()
            readAll(reader, "N1 G28*18\n")

            reader.read(line("N2 M117 M110 N0*37"))

            assertEquals(2, reader.lastLine)
        }
    }

    @Nested
    inner class LinesThatConsumeNoNumber {

        @Test
        fun `blank and comment-only lines are accepted and consume nothing`() {
            // spec 5: "a line number is only consumed by a line that is actually transmitted".
            val reader = GCodeReader()
            reader.read(line("N1 G28*18"))

            val receipts = readAll(reader, "\n   \n; just a comment\n(and another)\n")

            assertTrue(receipts.all { it is GAccepted && it.number == null }) { "$receipts" }
            assertEquals(1, reader.lastLine)
        }

        @Test
        fun `an unnumbered command line is accepted in the optional mode`() {
            // spec 7.2: line numbers are optional and conventionally absent in files.
            val reader = GCodeReader(GNumbering.OPTIONAL)

            val receipt = reader.read(line("G28"))

            assertInstanceOf(GAccepted::class.java, receipt)
            assertNull((receipt as GAccepted).number)
            assertEquals(0, reader.lastLine)
        }

        @Test
        fun `an unnumbered command line is refused in the required mode`() {
            val reader = GCodeReader(GNumbering.REQUIRED)

            val rejected = reader.read(line("G28")) as GUnnumbered

            assertEquals("Error:No Line Number with checksum, Last Line: 0", rejected.msg)
            assertEquals(1, rejected.resend)
        }

        @Test
        fun `a blank line is accepted even in the required mode`() {
            // It is not a transmitted line, so there is nothing to number.
            val reader = GCodeReader(GNumbering.REQUIRED)

            assertInstanceOf(GAccepted::class.java, reader.read(line("; comment\n")))
        }
    }

    @Nested
    inner class BadLines {

        @Test
        fun `a failed checksum is a resend request`() {
            val reader = GCodeReader()

            val rejected = reader.read(line("N1 G28*12")) as GCorrupted

            assertEquals(18, rejected.expected)
            assertEquals(12, rejected.received)
            assertEquals("Error:checksum mismatch, Last Line: 0", rejected.msg)
            assertEquals(1, rejected.resend)
            assertEquals(0, reader.lastLine)
        }

        @Test
        fun `the checksum is judged before the line number`() {
            // Both faults at once: N9 is out of sequence *and* its checksum is wrong. spec 8.5 puts
            // the checksum first - RepRapFirmware validates it at buffer-fill time, before the line
            // number is looked at anywhere - and this module follows the spec rather than Marlin,
            // which checks the number first. Both reject and both ask for the same resend, so the
            // only thing riding on it is which message the host sees.
            val reader = GCodeReader()

            assertInstanceOf(GCorrupted::class.java, reader.read(line("N9 G28*12")))
        }

        @Test
        fun `a line number without a checksum is refused`() {
            val reader = GCodeReader()

            val rejected = reader.read(line("N1 G28")) as GUnchecksummed

            assertEquals("Error:No Checksum with line number, Last Line: 0", rejected.msg)
        }

        @Test
        fun `a checksum without a line number is refused`() {
            val reader = GCodeReader()

            val rejected = reader.read(line("G28*21")) as GUnnumbered

            assertEquals("Error:No Line Number with checksum, Last Line: 0", rejected.msg)
        }

        @Test
        fun `a garbled field is refused as malformed rather than as a mismatch`() {
            val reader = GCodeReader()

            assertInstanceOf(GMalformed::class.java, reader.read(line("N1 G28*ABC")))
            assertInstanceOf(GMalformed::class.java, reader.read(line("N G28*18")))
        }

        @Test
        fun `no bad line moves the counter`() {
            val reader = GCodeReader()
            readAll(reader, "N1 G28*18\n")

            readAll(reader, "N2 G28*99\nN2 G28\nG28*21\nN2 G28*ABC\nN7 G28*30\n")

            assertEquals(1, reader.lastLine)
        }
    }

    @Nested
    inner class TheResendLoop {

        @Test
        fun `a lost line is recovered by rewinding to the requested number`() {
            // spec 8.5 end to end: N2 is lost, N3 arrives, the reader asks for 2, the host rewinds
            // and resends 2 and 3, and the session carries on.
            val reader = GCodeReader()
            assertInstanceOf(GAccepted::class.java, reader.read(line("N1 G28*18")))

            val rejected = reader.read(line("N3 T0*57")) as GRejected
            assertEquals(2, rejected.resend)

            assertInstanceOf(GAccepted::class.java, reader.read(line("N2 G28*17")))
            assertInstanceOf(GAccepted::class.java, reader.read(line("N3 T0*57")))
            assertEquals(3, reader.lastLine)
        }

        @Test
        fun `a resend that crosses a retransmission in flight settles`() {
            // The race GDuplicate exists for. The host retransmits N2 on its own, then the Resend
            // for N2 arrives and it sends N2 again. The second copy must be dropped in silence - if
            // it produced another resend request the loop would not terminate.
            val reader = GCodeReader()
            readAll(reader, "N1 G28*18\n")

            assertInstanceOf(GAccepted::class.java, reader.read(line("N2 G28*17")))
            assertInstanceOf(GDuplicate::class.java, reader.read(line("N2 G28*17")))
            assertInstanceOf(GAccepted::class.java, reader.read(line("N3 T0*57")))
        }
    }

    /**
     * Spec 7.1's two dialect quirks, pinned deliberately rather than discovered later.
     */
    @Nested
    inner class DialectQuirks {

        @Test
        fun `a signed line number behaves as Marlin makes it behave`() {
            // spec 7.1: "Marlin's parser skips `N` followed by any run of `-` and digits", so `N-1`
            // is a line number of -1 rather than a syntax error, and the liner parses it as a packet.
            // Marlin then finds -1 != last_N + 1, falls into WITHIN(-1, -1, 0), and drops the line
            // in silence. This reader reaches the same verdict by the same route, which is worth an
            // assertion precisely because it looks like an accident.
            val reader = GCodeReader()

            val receipt = reader.read(line("N-1 G28*63"))

            assertInstanceOf(GDuplicate::class.java, receipt)
            assertEquals(0, reader.lastLine)
        }

        @Test
        fun `a fractional line number is not one`() {
            // spec 7.1: RS274/NGC allows `N` followed by a period and a second integer. This module
            // does not - the liner wants a GInt and a decimal is not one - so the line is refused as
            // malformed rather than silently truncated to its integer part. Characterisation: if
            // RS274 line numbers are ever wanted they belong with todo 09's other RS274 gaps.
            val reader = GCodeReader()

            assertInstanceOf(GMalformed::class.java, reader.read(line("N1.5 G28*30")))
            assertEquals(0, reader.lastLine)
        }
    }

    @Nested
    inner class TheBoundary {

        @Test
        fun `the reader is driven by a plain iterator and holds no io`() {
            // The state lives here and nothing else does: no Mono, no Flux, no coroutine, no stream.
            // That is the whole point of putting the session in the portable core - the transport
            // layer drives it, it does not drive the transport.
            val reader = GCodeReader()
            val iterator = GLiner(GTokenizer.parse("N1 G28*18\nN2 G28*17\n").iterator())

            var count = 0
            while (iterator.hasNext()) {
                reader.read(iterator.next())
                count++
            }

            assertEquals(2, count)
            assertEquals(2, reader.lastLine)
        }

        @Test
        fun `two readers are independent`() {
            val first = GCodeReader()
            val second = GCodeReader()

            first.read(line("N1 G28*18"))

            assertEquals(1, first.lastLine)
            assertEquals(0, second.lastLine)
        }

        @Test
        fun `a reader can be started at a given line number`() {
            val reader = GCodeReader(first = 40)

            assertInstanceOf(GAccepted::class.java, reader.read(line("N41 G28*38")))
        }
    }
}
