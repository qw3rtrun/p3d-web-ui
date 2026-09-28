package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Tests for the spec 2.1 / 2.3 pairing rule, `valueIndex`: an identifier, optional whitespace, then
 * the value it carries.
 *
 * Characterisation tests: every case here is pinned against the behaviour the rule already has, so
 * that moving it between packages is shown not to change a single answer.
 */
class GPairingTest {

    private fun pairedAt(gcode: String): Int = valueIndex(GTokenizer.parse(gcode).toList(), 0)

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

            assertEquals(-1, assertDoesNotThrow<Int> { valueIndex(tokens, tokens.size - 1) })
        }
    }
}
