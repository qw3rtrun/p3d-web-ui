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
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G12 [P<value>] [R<radius>] [S<count>] [T<count>] [X] [Y] [Z]
 *
 * Clean the Nozzle (nozzle).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G12.html">MarlinFirmare G12 doc</a>
 */
data class CleanTheNozzle(
    /** `P` */
    val p: Int? = null,
    /** `R` - radius */
    val radius: BigDecimal? = null,
    /** `S` - count */
    val count: Int? = null,
    /** `T` - count */
    val t: Int? = null,
    /** `X` */
    val x: Boolean = false,
    /** `Y` */
    val y: Boolean = false,
    /** `Z` */
    val z: Boolean = false,
) : GRq<CleanTheNozzle> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (p != null) words.add(word('P', p))
        if (radius != null) words.add(word('R', radius.toPlainString()))
        if (count != null) words.add(word('S', count))
        if (t != null) words.add(word('T', t))
        if (x) words.add(flag('X'))
        if (y) words.add(flag('Y'))
        if (z) words.add(flag('Z'))
        return G(12, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<CleanTheNozzle> {

        override fun head(): GParameterWord<*> {
            return G(12).head
        }

        override fun decodeParams(params: List<GWord>): CleanTheNozzle {
            return CleanTheNozzle(
                p = params.intOf('P'),
                radius = params.decimalOf('R'),
                count = params.intOf('S'),
                t = params.intOf('T'),
                x = params.hasWord('X'),
                y = params.hasWord('Y'),
                z = params.hasWord('Z'),
            )
        }
    }
}
