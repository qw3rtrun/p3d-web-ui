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
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M125 [L<linear>] [X<linear>] [Y<linear>] [Z<linear>] [P<value>]
 *
 * Park Head (nozzle).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M125.html">MarlinFirmare M125 doc</a>
 */
data class ParkHead(
    /** `L` - linear */
    val linear: BigDecimal? = null,
    /** `X` - linear */
    val x: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
    /** `Z` - linear */
    val z: BigDecimal? = null,
    /** `P` */
    val p: Boolean? = null,
) : GRq<ParkHead> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (linear != null) words.add(word('L', linear.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (p != null) words.add(word('P', if (p) 1 else 0))
        return M(125, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ParkHead> {

        override fun head(): GParameterWord<*> {
            return M(125).head
        }

        override fun decodeParams(params: List<GWord>): ParkHead {
            return ParkHead(
                linear = params.decimalOf('L'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                p = params.boolOf('P'),
            )
        }
    }
}
