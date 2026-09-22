package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.GEncoder

/**
 * The command model of GCODE_spec.md section 4: a head word, its parameter words, and what
 * [GEncoder] makes of them. The types live one layer above a line - a line is tokens, and only
 * `GCommandParser` turns tokens into the words these are built from.
 */
class GCommandsTest {

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
