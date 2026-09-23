package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The line model of GCODE_spec.md section 5: what each line kind carries, and the sealed hierarchy
 * consumers dispatch on. Every line is a list of tokens; the framing fields a packet read off itself
 * are values beside them, never a second representation of the line.
 */
class GLinesTest {

    @Nested
    inner class Kinds {

        /** What a line is made of: tokens. `G28`, with nothing around it. */
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
                GMissingLineNumber(raw),
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
            assertEquals("error", kind(GMissingLineNumber(raw)))
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

            // "every structural error is a line" is a compile-time claim, so this is where it is
            // made - the list widens with no cast. Asserting `it is GLine` at runtime said nothing
            // the compiler had not already proved, and warned that it was always true.
            val asLines: List<GLine> = errors

            assertTrue(asLines.all { it.raw == raw })
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

}
