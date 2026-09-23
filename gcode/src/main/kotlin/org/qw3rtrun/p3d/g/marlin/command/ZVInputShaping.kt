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
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M593 [D<zeta>] [F<hertz>] [X] [Y] [Z]
 *
 * ZV Input Shaping (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M593.html">MarlinFirmare M593 doc</a>
 */
data class ZVInputShaping(
    /** `D` - zeta */
    val zeta: BigDecimal? = null,
    /** `F` - hertz */
    val hertz: BigDecimal? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
) : GRq<ZVInputShaping> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (zeta != null) words.add(word('D', zeta.toPlainString()))
        if (hertz != null) words.add(word('F', hertz.toPlainString()))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        return M(593, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ZVInputShaping> {

        override fun head(): GParameterWord<*> {
            return M(593).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): ZVInputShaping {
            val params = tokens.toList()
            return ZVInputShaping(
                zeta = params.decimalOf('D'),
                hertz = params.decimalOf('F'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
            )
        }
    }
}
