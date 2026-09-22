package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.junit.jupiter.api.Test

/**
 * Tests for the line/command model of GCODE_spec.md section 5 - construction, rendering and the
 * sealed hierarchy that consumers dispatch on.
 */
class GSemanticsTest {

    @Nested
    inner class Commands {

        @Test
        fun `a bare command renders letter and number`() {
            assertEquals("G28", GEncoder.encode(GCommand(GLetter('G'), GInt(28))))
        }

        @Test
        fun `a command without params renders just its head`() {
            assertEquals("G28", GEncoder.encode(GCommand(GLetter('G'), GInt(28))))
        }

        @Test
        fun `params default to an empty list`() {
            assertEquals(emptyList<GWord>(), GCommand(GLetter('G'), GInt(28)).params)
        }

        @Test
        fun `a command renders its parameter words`() {
            val command = GCommand(
                GLetter('G'),
                GInt(1),
                listOf(
                    GParameterWord(GLetter('X'), GFloat("10.5")),
                    GParameterWord(GLetter('F'), GInt(1800))
                )
            )

            assertEquals("G1 X10.5 F1800", GEncoder.encode(command))
        }

        @Test
        fun `a command renders a quoted string parameter`() {
            val command = GCommand(
                GLetter('M'),
                GInt(117),
                listOf(GParameterWord(GLetter('S'), GQuotedString("Hello!")))
            )

            assertEquals("M117 S\"Hello!\"", GEncoder.encode(command))
        }

        @Test
        fun `a command renders an expression parameter`() {
            val command = GCommand(
                GLetter('M'),
                GInt(140),
                listOf(GParameterWord(GLetter('S'), GRawExpression("{bed[0]}")))
            )

            assertEquals("M140 S{bed[0]}", GEncoder.encode(command))
        }

        @Test
        fun `the identifier and number constructor creates expected head`() {
            val fromIdAndNum = GCommand(GLetter('M'), GInt(104), listOf(GParameterWord(GLetter('S'), GInt(200))))
            val explicit = GCommand(GParameterWord(GLetter('M'), GInt(104)), listOf(GParameterWord(GLetter('S'), GInt(200))))

            assertEquals(explicit, fromIdAndNum)
            assertEquals("M104 S200", GEncoder.encode(fromIdAndNum))
        }

        @Test
        fun `the identifier and number constructor works without extra params`() {
            val command = GCommand(GLetter('T'), GInt(0))

            assertEquals(GParameterWord(GLetter('T'), GInt(0)), command.head)
            assertEquals(emptyList<GWord>(), command.params)
            assertEquals("T0", GEncoder.encode(command))
        }

        @Test
        fun `commands with equal head and params are equal`() {
            assertEquals(
                GCommand(GLetter('G'), GInt(1)),
                GCommand(GLetter('G'), GInt(1))
            )
            assertFalse(GCommand(GLetter('G'), GInt(1)) == GCommand(GLetter('G'), GInt(2)))
        }

        @Test
        fun `a command is not a line`() {
            val command = GCommand(GLetter('G'), GInt(1))

            assertFalse(command as Any is GLine)
        }
    }

    @Nested
    inner class Lines {

        /** What a line is made of now: tokens. `G28`, with nothing around it. */
        private val raw = listOf<GToken>(GLetter('G'), GInt(28))

        @Test
        fun `an empty line carries no tokens`() {
            assertEquals(emptyList<GToken>(), GMeaninglessLine(emptyList()).raw)
            assertEquals(GMeaninglessLine(emptyList()), GMeaninglessLine(emptyList()))
        }

        @Test
        fun `a simple line keeps its tokens`() {
            assertEquals(raw, GSimpleLine(raw).raw)
        }

        @Test
        fun `the body of an unframed line is the whole line`() {
            assertEquals(raw, GSimpleLine(raw).body)
            assertEquals(raw, GMeaninglessLine(raw).body)
            assertEquals(raw, GMissingLineNumber(raw).body)
        }

        @Test
        fun `a packet exposes its number, its checksum, its body and its whole line`() {
            val whole = listOf<GToken>(
                GLetter('N'), GInt(3), GSpace, GLetter('T'), GInt(0), GChecksum, GInt(57),
            )
            val line = GPacketLine(GInt(3), GInt(57), whole.subList(2, 5), whole)

            assertEquals(GInt(3), line.number)
            assertEquals(GInt(57), line.checksum)
            assertEquals(listOf<GToken>(GSpace, GLetter('T'), GInt(0)), line.body)
            assertEquals(whole, line.raw)
        }

        @Test
        fun `a packet prints its whole line, not its body`() {
            // The framing fields are tokens of the line like any other, so a packet reproduces its
            // input the same way every other line does. This is the shape of TODO 1.20, which could
            // only exist while a packet printed a decomposed part of itself.
            val whole = listOf<GToken>(
                GLetter('N'), GInt(3), GSpace, GLetter('T'), GInt(0), GChecksum, GInt(57),
            )
            val line = GPacketLine(GInt(3), GInt(57), whole.subList(2, 5), whole)

            assertEquals("N3 T0*57", line.raw.joinToString("") { it.rawText() })
        }

        @Test
        fun `a packet is reachable through the ordered and checksum interfaces`() {
            val line: GLine = GPacketLine(GInt(3), GInt(57), emptyList(), raw)

            assertTrue(line is GOrdered)
            assertTrue(line is GCheckSumControlled)
            assertEquals(GInt(3), (line as GOrdered).number)
            assertEquals(GInt(57), (line as GCheckSumControlled).checksum)
        }

        @Test
        fun `a simple line is neither ordered nor checksum controlled`() {
            val line: GLine = GSimpleLine(raw)

            assertFalse(line is GOrdered)
            assertFalse(line is GCheckSumControlled)
            assertFalse(line is GError)
        }

        @Test
        fun `every line kind keeps the tokens it was built from`() {
            val lines: List<GLine> = listOf(
                GMeaninglessLine(raw),
                GSimpleLine(raw),
                GPacketLine(GInt(1), GInt(1), emptyList(), raw),
                GNotIdentifierError(GInt(1), raw),
            )

            assertTrue(lines.all { it.raw == raw })
        }

        @Test
        fun `the line hierarchy dispatches exhaustively`() {
            // Compile-time guard: adding a GLine subtype without handling it breaks this `when`.
            fun kind(line: GLine): String = when (line) {
                is GMeaninglessLine -> "empty"
                is GSimpleLine -> "simple"
                is GPacketLine -> "packet"
                is GError -> "error"
            }

            assertEquals("empty", kind(GMeaninglessLine(emptyList())))
            assertEquals("simple", kind(GSimpleLine(raw)))
            assertEquals("packet", kind(GPacketLine(GInt(1), GInt(1), emptyList(), raw)))
            assertEquals("error", kind(GNotIdentifierError(GInt(1), raw)))
        }

        @Test
        fun `the structural errors carry the line and a message`() {
            // GCODE_spec.md section 7.3 and section 9 - structural errors. Each one keeps the whole
            // line so a caller can log it, resend it, or decide the severity for itself.
            assertEquals("line number 42 has no checksum", GMissingChecksum(GInt(42), raw).msg)
            assertEquals("line number ? has no checksum", GMissingChecksum(null, raw).msg)
            assertEquals("checksum without a line number", GMissingLineNumber(raw).msg)
            assertEquals("'N' is not followed by a line number", GMalformedLineNumber(raw).msg)
            assertEquals(
                "'*' is not followed by a checksum value on line 7",
                GMalformedChecksum(GInt(7), raw).msg
            )
        }

        @Test
        fun `every structural error is a GError and keeps its tokens`() {
            val errors: List<GError> = listOf(
                GMissingChecksum(GInt(1), raw),
                GMissingLineNumber(raw),
                GMalformedLineNumber(raw),
                GMalformedChecksum(GInt(1), raw)
            )

            assertTrue(errors.all { it is GLine }) { "expected all of $errors to be GLine" }
            assertTrue(errors.all { it.raw == raw })
            assertTrue(errors.none { it is GOrdered || it is GCheckSumControlled })
        }

        @Test
        fun `a missing checksum keeps the line number it did find`() {
            assertEquals(GInt(42), GMissingChecksum(GInt(42), raw).number)
            assertEquals(null, GMissingChecksum(null, raw).number)
        }
    }

    @Nested
    inner class Checksums {

        @Test
        fun `a checksum is the value behind the marker, not a word`() {
            // The marker itself is a token of the line (GLine.raw), so the field the line has to
            // carry is the number - which is also all a host compares, resends or reports on.
            val line = GPacketLine(GInt(1), GInt(57), emptyList(), emptyList())

            assertEquals(GInt(57), line.checksum)
        }

        @Test
        fun `a checksum keeps the lexeme that chose its algorithm`() {
            // spec 8.1: the digit count selects xor from crc16, and a crc is zero-padded (8.4), so
            // the width has to survive - GInt(6939) and GInt(6939, "06939") are different fields.
            val crc = GPacketLine(GInt(1), GInt(6939, "06939"), emptyList(), emptyList())

            assertEquals("06939", crc.checksum.lexeme)
            assertEquals(6939, crc.checksum.int)
        }
    }

    @Nested
    inner class Errors {

        private val raw = listOf<GToken>(GUnknown("?"), GLetter('G'), GInt(28))

        @Test
        fun `a line that does not start with an identifier reports the offending token`() {
            val error = GNotIdentifierError(GInt(5), raw)

            assertEquals("GCode should start with a letter, but '5'", error.msg)
            assertEquals(GInt(5), error.head)
            assertEquals(raw, error.raw)
        }

        @Test
        fun `the message quotes the raw text of the offending token`() {
            assertEquals(
                "GCode should start with a letter, but '5'",
                GNotIdentifierError(GInt(5), raw).msg
            )
            assertEquals(
                "GCode should start with a letter, but '{x}'",
                GNotIdentifierError(GRawExpression("{x}"), raw).msg
            )
        }

        @Test
        fun `an error is a line`() {
            val error: GLine = GNotIdentifierError(GInt(5), raw)

            assertTrue(error is GError)
        }
    }
}
