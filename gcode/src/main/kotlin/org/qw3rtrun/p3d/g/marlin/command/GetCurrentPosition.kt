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
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M114 [D] [E] [R]
 *
 * Get Current Position (hosts).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M114.html">MarlinFirmare M114 doc</a>
 */
data class GetCurrentPosition(
    /** `D` */
    val d: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `R` */
    val r: Boolean = false,
) : GRq<GetCurrentPosition> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (d) words.add(flag('D'))
        if (e) words.add(flag('E'))
        if (r) words.add(flag('R'))
        return M(114, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<GetCurrentPosition> {

        override fun head(): GParameterWord<*> {
            return M(114).head
        }

        override fun decodeParams(params: List<GWord>): GetCurrentPosition {
            return GetCurrentPosition(
                d = params.hasWord('D'),
                e = params.hasWord('E'),
                r = params.hasWord('R'),
            )
        }
    }
}
