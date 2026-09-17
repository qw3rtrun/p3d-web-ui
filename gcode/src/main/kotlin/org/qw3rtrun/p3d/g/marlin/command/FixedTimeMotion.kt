// GENERATED FILE - do not edit.
//
// Regenerate with:  python3 tools/marlin/gen_mcommands.py
// Source of truth:  doc/marlin-gcode/commands.json
// Extracted from:   MarlinFirmware/MarlinDocumentation @ 0856d12b0253378bc8d17a246fb09fc8c5437997
// How and why:      tools/marlin/README.md, doc/todos/11-marlin-commands.md

package org.qw3rtrun.p3d.g.marlin.command

import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M493 [S<value>] [H<value>] [C<value>] [D<value>] [A<value>] [F<scale>] [I<zeta>] [Q<vtol>] [X] [Y] [Z] [E]
 *
 * Fixed-Time Motion (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M493.html">MarlinFirmare M493 doc</a>
 */
data class FixedTimeMotion(
    /** `S` */
    val s: Int? = null,
    /** `H` */
    val h: Int? = null,
    /** `C` */
    val c: Int? = null,
    /** `D` */
    val d: Int? = null,
    /** `A` */
    val a: BigDecimal? = null,
    /** `F` - scale */
    val scale: BigDecimal? = null,
    /** `I` - zeta */
    val zeta: BigDecimal? = null,
    /** `Q` - vtol */
    val vtol: BigDecimal? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
    /** `E` */
    val e: Boolean = false,
) : GRq<FixedTimeMotion> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(12)
        if (s != null) words.add(word('S', s))
        if (h != null) words.add(word('H', h))
        if (c != null) words.add(word('C', c))
        if (d != null) words.add(word('D', d))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (scale != null) words.add(word('F', scale.toPlainString()))
        if (zeta != null) words.add(word('I', zeta.toPlainString()))
        if (vtol != null) words.add(word('Q', vtol.toPlainString()))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        if (e) words.add(flag('E'))
        return M(493, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<FixedTimeMotion> {

        override fun head(): GParameterWord<*> {
            return M(493).head
        }

        override fun decodeParams(params: List<GWord>): FixedTimeMotion {
            return FixedTimeMotion(
                s = params.intOf('S'),
                h = params.intOf('H'),
                c = params.intOf('C'),
                d = params.intOf('D'),
                a = params.decimalOf('A'),
                scale = params.decimalOf('F'),
                zeta = params.decimalOf('I'),
                vtol = params.decimalOf('Q'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
                e = params.hasWord('E'),
            )
        }
    }
}
