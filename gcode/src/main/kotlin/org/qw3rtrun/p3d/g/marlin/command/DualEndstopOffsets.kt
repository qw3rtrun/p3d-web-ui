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
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M666 [X<adj>] [Y<adj>] [Z<adj>]
 *
 * Dual endstop offsets (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M666.html">MarlinFirmare M666 doc</a>
 */
data class DualEndstopOffsets(
    /** `X` - adj */
    val adj: BigDecimal? = null,
    /** `Y` - adj */
    val y: BigDecimal? = null,
    /** `Z` - adj */
    val z: BigDecimal? = null,
) : GRq<DualEndstopOffsets> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (adj != null) words.add(word('X', adj.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(666, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<DualEndstopOffsets> {

        override fun head(): GParameterWord<*> {
            return M(666).head
        }

        override fun decodeParams(params: List<GWord>): DualEndstopOffsets {
            return DualEndstopOffsets(
                adj = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
            )
        }
    }
}
