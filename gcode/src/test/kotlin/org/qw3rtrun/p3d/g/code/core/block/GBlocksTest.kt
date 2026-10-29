package org.qw3rtrun.p3d.g.code.core.block

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.token.GChecksum
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GLetter
import org.qw3rtrun.p3d.g.code.core.token.GSpace
import org.qw3rtrun.p3d.g.code.core.token.GToken

/**
 * The block model of GCODE_spec.md section 5: what each block kind carries, and the sealed hierarchy
 * consumers dispatch on. Every block is a list of tokens; the framing fields a framed block read off itself
 * are values beside them, never a second representation of the block.
 */
class GBlocksTest {

    @Nested
    inner class Kinds {

        /** What a block is made of: tokens. `G28`, with nothing around it. */
        private val raw = listOf<GToken>(GLetter('G'), GInt(28))

        @Test
        fun `an empty block carries no tokens`() {
            assertEquals(emptyList<GToken>(), GMeaninglessBlock(emptyList()).raw)
            assertEquals(GMeaninglessBlock(emptyList()), GMeaninglessBlock(emptyList()))
        }

        @Test
        fun `a simple block keeps its tokens`() {
            assertEquals(raw, GPlainBlock(raw).raw)
        }

        @Test
        fun `the body of an unframed block is the whole block`() {
            assertEquals(raw, GPlainBlock(raw).body)
            assertEquals(raw, GMeaninglessBlock(raw).body)
            assertEquals(raw, GMissingLineNumber(raw).body)
        }

        @Test
        fun `a framed block exposes its number, its checksum, its body and its whole block`() {
            val whole = listOf<GToken>(
                GLetter('N'), GInt(3), GSpace, GLetter('T'), GInt(0), GChecksum, GInt(57),
            )
            val block = GFramedBlock(GInt(3), GInt(57), whole.subList(2, 5), whole)

            assertEquals(GInt(3), block.number)
            assertEquals(GInt(57), block.checksum)
            assertEquals(listOf<GToken>(GSpace, GLetter('T'), GInt(0)), block.body)
            assertEquals(whole, block.raw)
        }

        @Test
        fun `a framed block prints its whole block, not its body`() {
            // The framing fields are tokens of the block like any other, so a framed block reproduces its
            // input the same way every other block does. This is the shape of TODO 1.20, which could
            // only exist while a framed block printed a decomposed part of itself.
            val whole = listOf<GToken>(
                GLetter('N'), GInt(3), GSpace, GLetter('T'), GInt(0), GChecksum, GInt(57),
            )
            val block = GFramedBlock(GInt(3), GInt(57), whole.subList(2, 5), whole)

            assertEquals("N3 T0*57", block.raw.joinToString("") { it.rawText() })
        }

        @Test
        fun `a framed block is reachable through the ordered and checksum interfaces`() {
            val block: GBlock = GFramedBlock(GInt(3), GInt(57), emptyList(), raw)

            assertTrue(block is GOrdered)
            assertTrue(block is GCheckSumControlled)
            assertEquals(GInt(3), (block as GOrdered).number)
            assertEquals(GInt(57), (block as GCheckSumControlled).checksum)
        }

        @Test
        fun `a simple block is neither ordered nor checksum controlled`() {
            val block: GBlock = GPlainBlock(raw)

            assertFalse(block is GOrdered)
            assertFalse(block is GCheckSumControlled)
            assertFalse(block is GError)
        }

        @Test
        fun `every block kind keeps the tokens it was built from`() {
            val blocks: List<GBlock> = listOf(
                GMeaninglessBlock(raw),
                GPlainBlock(raw),
                GFramedBlock(GInt(1), GInt(1), emptyList(), raw),
                GMissingLineNumber(raw),
            )

            assertTrue(blocks.all { it.raw == raw })
        }

        @Test
        fun `the block hierarchy dispatches exhaustively`() {
            // Compile-time guard: adding a GBlock subtype without handling it breaks this `when`.
            fun kind(block: GBlock): String = when (block) {
                is GMeaninglessBlock -> "empty"
                is GPlainBlock -> "simple"
                is GFramedBlock -> "framed"
                is GError -> "error"
            }

            assertEquals("empty", kind(GMeaninglessBlock(emptyList())))
            assertEquals("simple", kind(GPlainBlock(raw)))
            assertEquals("framed", kind(GFramedBlock(GInt(1), GInt(1), emptyList(), raw)))
            assertEquals("error", kind(GMissingLineNumber(raw)))
        }

        @Test
        fun `the structural errors carry the block and a message`() {
            // GCODE_spec.md section 7.3 and section 9 - structural errors. Each one keeps the whole
            // block so a caller can log it, resend it, or decide the severity for itself.
            assertEquals("line number 42 has no checksum", GMissingChecksum(GInt(42), raw).msg)
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

            // "every structural error is a block" is a compile-time claim, so this is where it is
            // made - the list widens with no cast. Asserting `it is GBlock` at runtime said nothing
            // the compiler had not already proved, and warned that it was always true.
            val asLines: List<GBlock> = errors

            assertTrue(asLines.all { it.raw == raw })

            // None of them is trustworthy - that is what GCheckSumControlled means, and an error is
            // precisely a block whose checksum did not establish anything.
            assertTrue(errors.none { it is GCheckSumControlled })
        }

        /**
         * The resend partition, as a property of the types rather than of a reader - see `GError`.
         *
         * This asserted `none { it is GOrdered }` while only the mismatch implemented it, which
         * froze the model the other way round: it read as "no structural error can be resent",
         * which is true of two of the four and false of the other two.
         */
        @Test
        fun `a structural error is addressable exactly when it names its line`() {
            val addressable: List<GOrdered> = listOf(
                GMissingChecksum(GInt(1), raw),
                GMalformedChecksum(GInt(1), raw),
            )
            assertTrue(addressable.all { it.number == GInt(1) })

            // The other half is a compile-time claim and cannot be written as an assertion:
            // `GOrdered` is sealed, so `GMissingLineNumber(raw) is GOrdered` is refused outright -
            // "Check for instance is always 'false'". That refusal is the proof. What a *reader*
            // produces for those two lines is asserted in GBlockReaderTest, where the static type
            // is GBlock and the question is real.
        }

        @Test
        fun `a missing checksum keeps the line number it did find`() {
            // Non-null by construction: the reader only builds one after it has read the number
            // (GBlockReader returns GMissingLineNumber when there is none), so the type says so
            // rather than every reader of it checking.
            assertEquals(GInt(42), GMissingChecksum(GInt(42), raw).number)
        }
    }

    @Nested
    inner class Checksums {

        @Test
        fun `a checksum is the value behind the marker, not a word`() {
            // The marker itself is a token of the block (GBlock.raw), so the field the block has to
            // carry is the number - which is also all a host compares, resends or reports on.
            val block = GFramedBlock(GInt(1), GInt(57), emptyList(), emptyList())

            assertEquals(GInt(57), block.checksum)
        }

        @Test
        fun `a checksum keeps the lexeme that chose its algorithm`() {
            // spec 8.1: the digit count selects xor from crc16, and a crc is zero-padded (8.4), so
            // the width has to survive - GInt(6939) and GInt(6939, "06939") are different fields.
            val crc = GFramedBlock(GInt(1), GInt(6939, "06939"), emptyList(), emptyList())

            assertEquals("06939", crc.checksum.lexeme)
            assertEquals(6939, crc.checksum.int)
        }
    }

}
