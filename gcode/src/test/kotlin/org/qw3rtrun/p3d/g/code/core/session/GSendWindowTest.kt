package org.qw3rtrun.p3d.g.code.core.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.Crc16CheckSum
import org.qw3rtrun.p3d.g.code.core.token.*

/**
 * The host's side of GCODE_spec.md section 8.5: the window of unacknowledged lines, and rewinding
 * it when the firmware asks for a resend.
 *
 * Deliberately **no I/O**. The window numbers, frames and remembers lines; sending them and reading
 * the replies belongs to the transport layer, which is a different module. That split is what the
 * todo called "the parsing half belongs here, the I/O half does not", and it is also what keeps the
 * session portable with the rest of `code/core`.
 */
class GSendWindowTest {

    private fun cmd(letter: Char, number: Int, vararg params: GWord) =
        GCommand(GLetter(letter), GInt(number), params.toList())

    private val g28 = cmd('G', 28)
    private val t0 = cmd('T', 0)

    @Nested
    inner class Numbering {

        @Test
        fun `each line is framed with the next number and a checksum`() {
            val window = GSendWindow()

            assertEquals("N1 G28*18", window.send(g28))
            assertEquals("N2 G28*17", window.send(g28))
            assertEquals("N3 T0*57", window.send(t0))
        }

        @Test
        fun `a session can start at a chosen number`() {
            // What a host does after `M110 N<n>`.
            val window = GSendWindow(first = 41)

            assertEquals("N41 G28*38", window.send(g28))
        }

        @Test
        fun `the crc can be used instead`() {
            val window = GSendWindow(calculator = ::Crc16CheckSum)

            assertEquals("N1 G28*14291", window.send(g28))
        }

        @Test
        fun `everything it emits parses back as a verified packet in sequence`() {
            // The property that ties this to the other half of the session: what the window sends,
            // a reader accepts. If the two ever disagree about numbering or the checksum, this says
            // so - and it is the only test here that exercises both directions.
            val window = GSendWindow(capacity = 64)
            val reader = GCodeReader()
            val tokenizer = GTokenizer()

            for (i in 0 until 20) {
                val text = requireNotNull(window.send(if (i % 2 == 0) g28 else t0))
                val line = GLiner(tokenizer.parse(text).iterator()).next()

                assertInstanceOf(GAccepted::class.java, reader.read(line)) { "rejected: $text" }
            }
            assertEquals(20, reader.lastLine)
        }
    }

    @Nested
    inner class TheWindow {

        @Test
        fun `the window holds at most its capacity`() {
            // spec 8.5: motion commands are buffered and `ok` acknowledges queuing, not completion,
            // so a host keeps a small window outstanding. Marlin's BUFSIZE default is 4.
            val window = GSendWindow(capacity = 4)

            for (i in 0 until 4) assertNotNull(window.send(g28)) { "slot $i should be free" }

            assertNull(window.send(g28))
            assertEquals(4, window.inFlight)
        }

        @Test
        fun `an acknowledgement frees a slot`() {
            val window = GSendWindow(capacity = 2)
            window.send(g28)
            window.send(g28)
            assertNull(window.send(g28))

            assertTrue(window.acknowledge())

            assertEquals(1, window.inFlight)
            assertEquals("N3 G28*16", window.send(g28))
        }

        @Test
        fun `acknowledging nothing is not an error but says so`() {
            assertFalse(GSendWindow().acknowledge())
        }

        @Test
        fun `a full window does not consume a line number`() {
            // The refused command was never framed, so the next one must still get N3 - otherwise a
            // rejected send would punch a gap in the sequence and the next line would be refused.
            val window = GSendWindow(capacity = 2)
            window.send(g28)
            window.send(g28)

            assertNull(window.send(t0))
            window.acknowledge()

            assertEquals("N3 T0*57", window.send(t0))
        }
    }

    @Nested
    inner class Resending {

        @Test
        fun `a resend replays from the requested number in order`() {
            val window = GSendWindow(capacity = 4)
            window.send(g28)
            window.send(g28)
            window.send(t0)

            assertEquals(listOf("N2 G28*17", "N3 T0*57"), window.resendFrom(2))
        }

        @Test
        fun `a resend rewinds the window so the replayed lines are outstanding again`() {
            val window = GSendWindow(capacity = 4)
            window.send(g28)
            window.send(g28)
            window.send(t0)
            window.acknowledge()

            assertEquals(2, window.inFlight)
            window.resendFrom(2)

            assertEquals(2, window.inFlight)
            // ...and numbering carries on after the replayed lines, not from the rewind point.
            assertEquals("N4 G28*23", window.send(g28))
        }

        @Test
        fun `the line the firmware asked for is always still held`() {
            // Not a coincidence worth relying on blindly, so it is asserted: `Resend: n` always has
            // n = last accepted + 1, and the host cannot have seen an `ok` for a line the firmware
            // never accepted, so n is never older than the oldest unacknowledged line.
            val window = GSendWindow(capacity = 4)
            window.send(g28)
            window.send(g28)
            window.send(t0)
            window.acknowledge()

            assertFalse(window.canResendFrom(1))
            assertTrue(window.canResendFrom(2))
            assertTrue(window.canResendFrom(3))
        }

        @Test
        fun `asking for a line that was dropped from the window yields nothing`() {
            // A peer asking outside the window has lost sync beyond what a resend can repair; the
            // host's answer is a fresh `M110`, not a replay. Reported as an empty replay rather than
            // as a thrown exception, because it is a statement about the session and not a bug in
            // the caller.
            val window = GSendWindow(capacity = 2)
            window.send(g28)
            window.send(g28)
            window.acknowledge()
            window.acknowledge()
            window.send(t0)

            assertFalse(window.canResendFrom(1))
            assertEquals(emptyList<String>(), window.resendFrom(1))
        }

        @Test
        fun `a resend replays the exact bytes that were sent`() {
            // Not a re-encoding: spec 8.3 makes the checksum a property of the bytes, so a replay
            // that re-rendered the command could differ from what was checksummed the first time.
            val window = GSendWindow(capacity = 4)
            val first = window.send(cmd('G', 1, GParameterWord(GLetter('X'), GFloat("10.50"))))

            assertEquals(listOf(first), window.resendFrom(1))
        }

        @Test
        fun `the resend loop settles against a reader`() {
            // End to end, both halves: line 2 is lost in transit, the reader asks for it, the
            // window replays from there, and the session catches up.
            val window = GSendWindow(capacity = 4)
            val reader = GCodeReader()
            val tokenizer = GTokenizer()
            fun deliver(text: String) =
                reader.read(GLiner(tokenizer.parse(text).iterator()).next())

            val one = requireNotNull(window.send(g28))
            val two = requireNotNull(window.send(g28))
            val three = requireNotNull(window.send(t0))

            assertInstanceOf(GAccepted::class.java, deliver(one))
            // `two` never arrives.
            val rejected = deliver(three) as GRejected
            assertEquals(2, rejected.resend)

            for (replay in window.resendFrom(rejected.resend)) {
                assertInstanceOf(GAccepted::class.java, deliver(replay)) { "replay rejected: $replay" }
            }
            assertEquals(3, reader.lastLine)
            assertEquals(two, window.resendFrom(2).first())
        }
    }

    @Nested
    inner class Resynchronising {

        @Test
        fun `reset emits an M110 and renumbers from there`() {
            // spec 7.2: `M110 N<n>` is how a host resynchronises. The M110 line itself is exempt
            // from the continuity check, which is what lets it be sent when the sequence is broken.
            val window = GSendWindow(capacity = 4)
            window.send(g28)
            window.send(g28)

            val reset = requireNotNull(window.reset(0))

            assertTrue(reset.startsWith("N")) { reset }
            assertTrue(reset.contains("M110 N0")) { reset }
            assertEquals("N1 G28*18", window.send(g28))
        }

        @Test
        fun `reset clears the outstanding window`() {
            // Whatever was in flight is abandoned: after a resynchronisation there is nothing left
            // to resend, because the numbers those lines carried no longer mean anything.
            val window = GSendWindow(capacity = 4)
            window.send(g28)
            window.send(g28)

            window.reset(0)

            assertEquals(0, window.inFlight)
            assertFalse(window.canResendFrom(1))
        }

        @Test
        fun `the reset line is itself accepted by a reader mid-sequence`() {
            val window = GSendWindow(capacity = 4)
            val reader = GCodeReader()
            val tokenizer = GTokenizer()
            reader.read(GLiner(tokenizer.parse("N1 G28*18").iterator()).next())

            val reset = requireNotNull(window.reset(0))
            val line = GLiner(tokenizer.parse(reset).iterator()).next()

            assertInstanceOf(GAccepted::class.java, reader.read(line))
            assertEquals(0, reader.lastLine)
        }
    }
}
