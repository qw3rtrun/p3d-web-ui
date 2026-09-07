package org.qw3rtrun.p3d.g.code

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GFloat
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GLetter
import org.qw3rtrun.p3d.g.code.core.token.GQuotedString
import org.qw3rtrun.p3d.g.code.core.token.toToken
import java.math.BigDecimal

/**
 * Tests for the command emitting DSL - every builder produces one [GCommand] with the expected head
 * letter (GCODE_spec.md section 4.1) and parameter words, and hands it to the output sink.
 */
class GTest {

    private val emitted = mutableListOf<GCommand>()

    private val g = G { emitted.add(it) }

    private fun single(): GCommand {
        assertEquals(1, emitted.size) { "expected exactly one emitted command, got $emitted" }
        return emitted.single()
    }

    @Test
    fun `G emits a G command with its number`() {
        g.G(28)

        assertEquals(GCommand(GLetter('G'), listOf(GInt(28))), single())
        assertEquals("G28", single().print())
    }

    @Test
    fun `M emits an M command with its number`() {
        g.M(105)

        assertEquals(GCommand(GLetter('M'), listOf(GInt(105))), single())
        assertEquals("M105", single().print())
    }

    @Test
    fun `T emits a T command with its number`() {
        g.T(0)

        assertEquals(GCommand(GLetter('T'), listOf(GInt(0))), single())
        assertEquals("T0", single().print())
    }

    @Test
    fun `parameters are appended after the command number`() {
        g.G(1, 'X'.toToken(), BigDecimal("10.5").toToken(), 'F'.toToken(), 1800.toToken())

        assertEquals(
            GCommand(
                GLetter('G'),
                listOf(GInt(1), GLetter('X'), GFloat(BigDecimal("10.5")), GLetter('F'), GInt(1800))
            ),
            single()
        )
        assertEquals("G1X10.5F1800", single().print())
    }

    @Test
    fun `a quoted string parameter is rendered with quotes`() {
        g.M(117, "Hello!".toToken())

        assertEquals(GCommand(GLetter('M'), listOf(GInt(117), GQuotedString("Hello!"))), single())
        assertEquals("M117\"Hello!\"", single().print())
    }

    @Test
    fun `the element overload accepts a prebuilt command number`() {
        g.G(GInt(28))

        assertEquals(GCommand(GLetter('G'), listOf(GInt(28))), single())
    }

    @Test
    fun `the int and element overloads agree`() {
        g.G(28)
        g.G(GInt(28))
        g.M(105)
        g.M(GInt(105))
        g.T(1)
        g.T(GInt(1))

        assertEquals(emitted[0], emitted[1])
        assertEquals(emitted[2], emitted[3])
        assertEquals(emitted[4], emitted[5])
    }

    @Test
    fun `each call emits exactly one command in order`() {
        g.M(140, 'S'.toToken(), 60.toToken())
        g.G(28)
        g.T(1)

        assertEquals(3, emitted.size)
        assertEquals(listOf("M140S60", "G28", "T1"), emitted.map { it.print() })
        assertEquals(listOf(GLetter('M'), GLetter('G'), GLetter('T')), emitted.map { it.head })
    }

    @Test
    fun `nothing is emitted until a builder is called`() {
        assertEquals(emptyList<GCommand>(), emitted)
    }
}
