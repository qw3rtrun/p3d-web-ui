package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
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
            assertEquals(listOf(GLetter('G'), GInt(28)), GCommand(GLetter('G'), GInt(28)).print())
        }

        @Test
        fun `a command without params renders just its head`() {
            assertEquals(listOf(GLetter('G'), GInt(28)), GCommand(GLetter('G'), GInt(28)).print())
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

            assertEquals(
                listOf(GLetter('G'), GInt(1), GLetter('X'), GFloat("10.5"), GLetter('F'), GInt(1800)),
                command.print()
            )
        }

        @Test
        fun `a command renders a quoted string parameter`() {
            val command = GCommand(
                GLetter('M'),
                GInt(117),
                listOf(GParameterWord(GLetter('S'), GQuotedString("Hello!")))
            )

            assertEquals(
                listOf(GLetter('M'), GInt(117), GLetter('S'), GQuotedString("Hello!")),
                command.print()
            )
        }

        @Test
        fun `a command renders an expression parameter`() {
            val command = GCommand(
                GLetter('M'),
                GInt(140),
                listOf(GParameterWord(GLetter('S'), GRawExpression("{bed[0]}")))
            )

            assertEquals(
                listOf(GLetter('M'), GInt(140), GLetter('S'), GRawExpression("{bed[0]}")),
                command.print()
            )
        }

        @Test
        fun `the identifier and number constructor creates expected head`() {
            val fromIdAndNum = GCommand(GLetter('M'), GInt(104), listOf(GParameterWord(GLetter('S'), GInt(200))))
            val explicit = GCommand(GParameterWord(GLetter('M'), GInt(104)), listOf(GParameterWord(GLetter('S'), GInt(200))))

            assertEquals(explicit, fromIdAndNum)
            assertEquals(listOf(GLetter('M'), GInt(104), GLetter('S'), GInt(200)), fromIdAndNum.print())
        }

        @Test
        fun `the identifier and number constructor works without extra params`() {
            val command = GCommand(GLetter('T'), GInt(0))

            assertEquals(GParameterWord(GLetter('T'), GInt(0)), command.head)
            assertEquals(emptyList<GWord>(), command.params)
            assertEquals(listOf(GLetter('T'), GInt(0)), command.print())
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

        private val payload = listOf<GSemantic>(GParameterWord(GLetter('G'), GInt(28)))

        @Test
        fun `empty meaningless line has no payload by default`() {
            assertEquals(emptyList<GSemantic>(), GMeaninglessLine(emptyList()).payload)
            assertEquals(GMeaninglessLine(emptyList()), GMeaninglessLine(emptyList()))
        }

        @Test
        fun `simple line keeps its payload`() {
            assertEquals(payload, GSimpleLine(payload).payload)
        }

        @Test
        fun `packet line exposes number checksum payload and raw`() {
            val checksum = GParameterWord(GChecksum, GInt(57))
            val raw = listOf<GSemantic>(GMeaningless(GTailComment(" c")))
            val line = GPacketLine(GInt(3), payload, checksum, raw)

            assertEquals(GInt(3), line.number)
            assertEquals(checksum, line.checksum)
            assertEquals(payload, line.payload)
            assertEquals(raw, line.raw)
        }

        @Test
        fun `packet line is reachable through the ordered and checksum interfaces`() {
            val line: GLine = GPacketLine(
                GInt(3), payload, GParameterWord(GChecksum, GInt(57)), emptyList()
            )

            assertTrue(line is GOrdered)
            assertTrue(line is GCheckSumControlled)
            assertEquals(GInt(3), (line as GOrdered).number)
            assertEquals(GInt(57), (line as GCheckSumControlled).checksum.value)
        }

        @Test
        fun `a simple line is neither ordered nor checksum controlled`() {
            val line: GLine = GSimpleLine(payload)

            assertFalse(line is GOrdered)
            assertFalse(line is GCheckSumControlled)
            assertFalse(line is GError)
        }

        @Test
        fun `every line kind has payload`() {
            val lines: List<GLine> = listOf(
                GMeaninglessLine(emptyList()),
                GSimpleLine(payload),
                GPacketLine(GInt(1), payload, GParameterWord(GChecksum, GInt(1)), emptyList()),
                GNotIdentifierError(GInt(1), payload)
            )

            assertTrue(lines.all { it.payload == payload || it.payload.isEmpty() })
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
            assertEquals("simple", kind(GSimpleLine(payload)))
            assertEquals(
                "packet",
                kind(GPacketLine(GInt(1), payload, GParameterWord(GChecksum, GInt(1)), emptyList()))
            )
            assertEquals("error", kind(GNotIdentifierError(GInt(1), payload)))
        }

        @Test
        fun `the structural errors carry the line and a message`() {
            // GCODE_spec.md section 7.3 and section 9 - structural errors. Each one keeps the whole
            // line so a caller can log it, resend it, or decide the severity for itself.
            assertEquals("line number 42 has no checksum", GMissingChecksum(GInt(42), payload).msg)
            assertEquals("line number ? has no checksum", GMissingChecksum(null, payload).msg)
            assertEquals("checksum without a line number", GMissingLineNumber(payload).msg)
            assertEquals("'N' is not followed by a line number", GMalformedLineNumber(payload).msg)
            assertEquals(
                "'*' is not followed by a checksum value on line 7",
                GMalformedChecksum(GInt(7), payload).msg
            )
        }

        @Test
        fun `every structural error is a GError and keeps its payload`() {
            val errors: List<GError> = listOf(
                GMissingChecksum(GInt(1), payload),
                GMissingLineNumber(payload),
                GMalformedLineNumber(payload),
                GMalformedChecksum(GInt(1), payload)
            )

            assertTrue(errors.all { it is GLine }) { "expected all of $errors to be GLine" }
            assertTrue(errors.all { it.payload == payload })
            assertTrue(errors.none { it is GOrdered || it is GCheckSumControlled })
        }

        @Test
        fun `a missing checksum keeps the line number it did find`() {
            assertEquals(GInt(42), GMissingChecksum(GInt(42), payload).number)
            assertEquals(null, GMissingChecksum(null, payload).number)
        }
    }

    @Nested
    inner class Checksums {

        @Test
        fun `checksum parameter word pairs the marker with its number`() {
            val checksum = GParameterWord(GChecksum, GInt(57))

            assertEquals(GChecksum, checksum.id)
            assertEquals(GInt(57), checksum.value)
        }

        @Test
        fun `checksum parameter words with the same number are equal`() {
            assertEquals(GParameterWord(GChecksum, GInt(57)), GParameterWord(GChecksum, GInt(57)))
            assertFalse(GParameterWord(GChecksum, GInt(57)) == GParameterWord(GChecksum, GInt(58)))
        }
    }

    @Nested
    inner class Errors {

        private val payload = listOf<GSemantic>(GMeaningless(GUnknown("?")), GParameterWord(GLetter('G'), GInt(28)))

        @Test
        fun `a line that does not start with an identifier reports the offending element`() {
            val error = GNotIdentifierError(GInt(5), payload)

            assertEquals("GCode should start with a letter, but '5'", error.msg)
            assertEquals(GInt(5), error.head)
            assertEquals(payload, error.payload)
        }

        @Test
        fun `the message quotes the raw text of the offending element`() {
            assertEquals(
                "GCode should start with a letter, but '5'",
                GNotIdentifierError(GInt(5), payload).msg
            )
            assertEquals(
                "GCode should start with a letter, but '{x}'",
                GNotIdentifierError(GRawExpression("{x}"), payload).msg
            )
        }

        @Test
        fun `an error is a line`() {
            val error: GLine = GNotIdentifierError(GInt(5), payload)

            assertTrue(error is GError)
        }
    }
}
