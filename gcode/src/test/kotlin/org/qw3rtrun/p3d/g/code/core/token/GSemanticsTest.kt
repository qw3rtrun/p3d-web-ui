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
            assertEquals("G28", GCommand(GLetter('G'), listOf(GInt(28))).print())
        }

        @Test
        fun `a command without params renders just its head`() {
            assertEquals("G", GCommand(GLetter('G')).print())
        }

        @Test
        fun `params default to an empty list`() {
            assertEquals(emptyList<GElement>(), GCommand(GLetter('G')).params)
        }

        @Test
        fun `a command renders its parameter words`() {
            val command = GCommand(
                GLetter('G'),
                listOf(GInt(1), GLetter('X'), GFloat("10.5"), GLetter('F'), GInt(1800))
            )

            assertEquals("G1X10.5F1800", command.print())
        }

        @Test
        fun `a command renders a quoted string parameter`() {
            val command = GCommand(GLetter('M'), listOf(GInt(117), GQuotedString("Hello!")))

            assertEquals("M117\"Hello!\"", command.print())
        }

        @Test
        fun `a command renders an expression parameter`() {
            val command = GCommand(GLetter('M'), listOf(GInt(140), GLetter('S'), GRawExpression("{bed[0]}")))

            assertEquals("M140S{bed[0]}", command.print())
        }

        @Test
        fun `the pair constructor prepends the head value to the params`() {
            val fromPair = GCommand(GLetter('M') to GInt(104), listOf(GLetter('S'), GInt(200)))
            val explicit = GCommand(GLetter('M'), listOf(GInt(104), GLetter('S'), GInt(200)))

            assertEquals(explicit, fromPair)
            assertEquals("M104S200", fromPair.print())
        }

        @Test
        fun `the pair constructor works without extra params`() {
            val command = GCommand(GLetter('T') to GInt(0))

            assertEquals(GLetter('T'), command.head)
            assertEquals(listOf(GInt(0)), command.params)
            assertEquals("T0", command.print())
        }

        @Test
        fun `commands with equal head and params are equal`() {
            assertEquals(
                GCommand(GLetter('G'), listOf(GInt(1))),
                GCommand(GLetter('G'), listOf(GInt(1)))
            )
            assertFalse(GCommand(GLetter('G'), listOf(GInt(1))) == GCommand(GLetter('G'), listOf(GInt(2))))
        }

        @Test
        fun `a command is a semantic node and not a line`() {
            val command: GSemantic = GCommand(GLetter('G'), listOf(GInt(1)))

            assertFalse(command is GLine)
        }
    }

    @Nested
    inner class Lines {

        private val payload = listOf<GToken>(GLetter('G'), GInt(28))

        @Test
        fun `empty line has no payload by default`() {
            assertEquals(emptyList<GToken>(), GEmptyLine().payload)
            assertEquals(GEmptyLine(emptyList()), GEmptyLine())
        }

        @Test
        fun `simple line keeps its payload`() {
            assertEquals(payload, GSimpleLine(payload).payload)
        }

        @Test
        fun `command line keeps both commands and payload`() {
            val commands = listOf(GCommand(GLetter('G'), listOf(GInt(28))))
            val line = GCommandLine(commands, payload)

            assertEquals(commands, line.cmds)
            assertEquals(payload, line.payload)
        }

        @Test
        fun `packet line exposes number checksum payload and tail`() {
            val checksum = GCheckSumValue(GChecksum, GInt(57))
            val tail = listOf<GToken>(GTailComment(" c"))
            val line = GPacketLine(GInt(3), payload, checksum, tail)

            assertEquals(GInt(3), line.number)
            assertEquals(checksum, line.checksum)
            assertEquals(payload, line.payload)
            assertEquals(tail, line.tail)
        }

        @Test
        fun `packet line is reachable through the ordered and checksum interfaces`() {
            val line: GLine = GPacketLine(
                GInt(3), payload, GCheckSumValue(GChecksum, GInt(57)), emptyList()
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
        fun `every line kind is a semantic node`() {
            val lines: List<GLine> = listOf(
                GEmptyLine(),
                GSimpleLine(payload),
                GCommandLine(emptyList(), payload),
                GPacketLine(GInt(1), payload, GCheckSumValue(GChecksum, GInt(1)), emptyList()),
                GNotIdentifierError(GUnknown("?"), payload)
            )

            assertTrue(lines.all { it is GSemantic }) { "expected all of $lines to be GSemantic" }
            assertTrue(lines.all { it.payload == payload || it.payload.isEmpty() })
        }

        @Test
        fun `the line hierarchy dispatches exhaustively`() {
            // Compile-time guard: adding a GLine subtype without handling it breaks this `when`.
            fun kind(line: GLine): String = when (line) {
                is GEmptyLine -> "empty"
                is GSimpleLine -> "simple"
                is GCommandLine -> "command"
                is GPacketLine -> "packet"
                is GError -> "error"
            }

            assertEquals("empty", kind(GEmptyLine()))
            assertEquals("simple", kind(GSimpleLine(payload)))
            assertEquals("command", kind(GCommandLine(emptyList(), payload)))
            assertEquals(
                "packet",
                kind(GPacketLine(GInt(1), payload, GCheckSumValue(GChecksum, GInt(1)), emptyList()))
            )
            assertEquals("error", kind(GNotIdentifierError(GUnknown("?"), payload)))
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
        fun `checksum value pairs the marker with its number`() {
            val checksum = GCheckSumValue(GChecksum, GInt(57))

            assertEquals(GChecksum, checksum.ident)
            assertEquals(GInt(57), checksum.value)
        }

        @Test
        fun `checksum values with the same number are equal`() {
            assertEquals(GCheckSumValue(GChecksum, GInt(57)), GCheckSumValue(GChecksum, GInt(57)))
            assertFalse(GCheckSumValue(GChecksum, GInt(57)) == GCheckSumValue(GChecksum, GInt(58)))
        }
    }

    @Nested
    inner class Errors {

        private val payload = listOf<GToken>(GUnknown("?"), GLetter('G'))

        @Test
        fun `a line that does not start with an identifier reports the offending element`() {
            val error = GNotIdentifierError(GUnknown("?"), payload)

            assertEquals("GCode should start with a letter, but '?'", error.msg)
            assertEquals(GUnknown("?"), error.head)
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
            val error: GLine = GNotIdentifierError(GUnknown("?"), payload)

            assertTrue(error is GError)
        }
    }
}
