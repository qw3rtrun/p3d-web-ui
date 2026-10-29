package org.qw3rtrun.p3d.g.code.core.block

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.qw3rtrun.p3d.g.code.core.token.GTokenizer

/**
 * Unit tests for reading a field off tokens - `GFields.kt`'s [valueIndex], GCODE_spec.md section
 * 2.1's pairing of an identifier with the value behind it.
 *
 * The rule is the module's only copy, so every reader above it - the reader's `N` and `*`, the
 * command head, the Marlin decoders - is measured here: whitespace is crossed, a comment, another
 * identifier or a line break ends the field, and only a `GValue` pairs.
 *
 * The head readers in the same file are measured by `GWordReaderTest` and the corpus contracts,
 * which exercise them over real blocks rather than token positions.
 */
class GFieldsTest {

    @Nested
    inner class Pairing {

        private fun pairedAt(gcode: String): Int = GTokenizer.parse(gcode).toList().valueIndex(0)

        @Nested
        inner class Pairs {

            @Test
            fun `a value directly behind its identifier is paired`() {
                assertEquals(1, pairedAt("N1"))
            }

            @Test
            fun `one space between identifier and value is crossed`() {
                assertEquals(2, pairedAt("N 1"))
            }

            @Test
            fun `several spaces are crossed`() {
                assertEquals(3, pairedAt("X  10"))
            }

            @Test
            fun `a tab is crossed`() {
                assertEquals(2, pairedAt("X\t10"))
            }

            @Test
            fun `a quoted string is a value`() {
                assertEquals(1, pairedAt("X\"a\""))
            }

            @Test
            fun `an expression is a value`() {
                assertEquals(1, pairedAt("X{a}"))
            }
        }

        @Nested
        inner class DoesNotPair {

            @Test
            fun `another identifier ends the field`() {
                assertEquals(-1, pairedAt("N*"))
                assertEquals(-1, pairedAt("X Y"))
            }

            @Test
            fun `an inline comment ends the field`() {
                assertEquals(-1, pairedAt("X (c) 10"))
            }

            @Test
            fun `a tail comment ends the field`() {
                assertEquals(-1, pairedAt("X;c"))
            }

            @Test
            fun `a line break is not whitespace`() {
                assertEquals(-1, pairedAt("X\n10"))
            }

            @Test
            fun `an unknown token is not a value`() {
                assertEquals(-1, pairedAt("X?"))
            }

            @Test
            fun `an identifier at the last index carries nothing and does not throw`() {
                val tokens = GTokenizer.parse("G1 X").toList()

                assertEquals(-1, assertDoesNotThrow<Int> { tokens.valueIndex(tokens.size - 1) })
            }
        }
    }
}
