// GENERATED FILE - do not edit.
//
// Regenerate with:  python3 tools/marlin/gen_mcommands.py
// Source of truth:  doc/marlin-gcode/commands.json
// Extracted from:   MarlinFirmware/MarlinDocumentation @ 0856d12b0253378bc8d17a246fb09fc8c5437997
// How and why:      tools/marlin/README.md, github.com/qw3rtrun/p3d-web-ui/issues/13

package org.qw3rtrun.p3d.g.marlin.command

import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G5 X<pos> Y<pos> [E<pos>] [F<rate>] I<pos> J<pos> P<pos> Q<pos> [S<power>]
 *
 * Bézier Cubic Spline Move (motion).
 *
 * Marlin documents `X`, `Y`, `I`, `J`, `P`, `Q` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G5.html">MarlinFirmare G5 doc</a>
 */
data class BezierCubicSplineMove(
    /** `X` - pos (required) */
    val pos: BigDecimal? = null,
    /** `Y` - pos (required) */
    val y: BigDecimal? = null,
    /** `E` - pos */
    val e: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `I` - pos (required) */
    val i: BigDecimal? = null,
    /** `J` - pos (required) */
    val j: BigDecimal? = null,
    /** `P` - pos (required) */
    val p: BigDecimal? = null,
    /** `Q` - pos (required) */
    val q: BigDecimal? = null,
    /** `S` - power */
    val power: BigDecimal? = null,
) : GRq<BezierCubicSplineMove> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (p != null) words.add(word('P', p.toPlainString()))
        if (q != null) words.add(word('Q', q.toPlainString()))
        if (power != null) words.add(word('S', power.toPlainString()))
        return G(5, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<BezierCubicSplineMove> {

        override fun head(): GParameterWord<*> {
            return G(5).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): BezierCubicSplineMove {
            val params = tokens.toList()
            return BezierCubicSplineMove(
                pos = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                e = params.decimalOf('E'),
                rate = params.decimalOf('F'),
                i = params.decimalOf('I'),
                j = params.decimalOf('J'),
                p = params.decimalOf('P'),
                q = params.decimalOf('Q'),
                power = params.decimalOf('S'),
            )
        }
    }
}
