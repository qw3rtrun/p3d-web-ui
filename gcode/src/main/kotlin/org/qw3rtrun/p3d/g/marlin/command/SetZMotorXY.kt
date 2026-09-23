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
 * M422 [R] [S<index>] [W<index>] [X<linear>] [Y<linear>]
 *
 * Set Z Motor XY (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M422.html">MarlinFirmare M422 doc</a>
 */
data class SetZMotorXY(
    /** `R` */
    val r: Boolean = false,
    /** `S` - index */
    val index: Int? = null,
    /** `W` - index */
    val w: Int? = null,
    /** `X` - linear */
    val linear: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
) : GRq<SetZMotorXY> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (r) words.add(flag('R'))
        if (index != null) words.add(word('S', index))
        if (w != null) words.add(word('W', w))
        if (linear != null) words.add(word('X', linear.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        return M(422, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetZMotorXY> {

        override fun head(): GParameterWord<*> {
            return M(422).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): SetZMotorXY {
            val params = tokens.toList()
            return SetZMotorXY(
                r = params.hasWord('R'),
                index = params.intOf('S'),
                w = params.intOf('W'),
                linear = params.decimalOf('X'),
                y = params.decimalOf('Y'),
            )
        }
    }
}
