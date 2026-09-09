package org.qw3rtrun.p3d.g.code

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GFloat
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GLetter
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
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

        assertEquals(GCommand(GLetter('G'), GInt(28)), single())
        assertEquals(listOf(GLetter('G'), GInt(28)), single().print())
    }

    @Test
    fun `M emits an M command with its number`() {
        g.M(105)

        assertEquals(GCommand(GLetter('M'), GInt(105)), single())
        assertEquals(listOf(GLetter('M'), GInt(105)), single().print())
    }

    @Test
    fun `T emits a T command with its number`() {
        g.T(0)

        assertEquals(GCommand(GLetter('T'), GInt(0)), single())
        assertEquals(listOf(GLetter('T'), GInt(0)), single().print())
    }

    @Test
    fun `parameters are appended after the command number`() {
        g.G(1, GParameterWord(GLetter('X'), BigDecimal("10.5").toToken()), GParameterWord(GLetter('F'), 1800.toToken()))

        assertEquals(
            GCommand(
                GLetter('G'),
                GInt(1),
                listOf(GParameterWord(GLetter('X'), GFloat(BigDecimal("10.5"))), GParameterWord(GLetter('F'), GInt(1800)))
            ),
            single()
        )
        assertEquals(listOf(GLetter('G'), GInt(1), GLetter('X'), GFloat(BigDecimal("10.5")), GLetter('F'), GInt(1800)), single().print())
    }

    @Test
    fun `a quoted string parameter is rendered with quotes`() {
        g.M(117, GParameterWord(GLetter('S'), "Hello!".toToken()))

        assertEquals(GCommand(GLetter('M'), GInt(117), listOf(GParameterWord(GLetter('S'), GQuotedString("Hello!")))), single())
        assertEquals(listOf(GLetter('M'), GInt(117), GLetter('S'), GQuotedString("Hello!")), single().print())
    }

    @Test
    fun `the element overload accepts a prebuilt command number`() {
        g.G(GInt(28))

        assertEquals(GCommand(GLetter('G'), GInt(28)), single())
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
        g.M(140, GParameterWord(GLetter('S'), 60.toToken()))
        g.G(28)
        g.T(1)

        assertEquals(3, emitted.size)
        assertEquals(
            listOf(
                listOf(GLetter('M'), GInt(140), GLetter('S'), GInt(60)),
                listOf(GLetter('G'), GInt(28)),
                listOf(GLetter('T'), GInt(1))
            ),
            emitted.map { it.print() }
        )
        assertEquals(listOf(GLetter('M'), GLetter('G'), GLetter('T')), emitted.map { it.head.id })
    }

    @Test
    fun `nothing is emitted until a builder is called`() {
        assertEquals(emptyList<GCommand>(), emitted)
    }
}
