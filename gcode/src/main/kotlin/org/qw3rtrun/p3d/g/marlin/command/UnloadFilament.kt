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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M702 [T<extruder>] [Z<distance>] U<distance>
 *
 * Unload filament (control).
 *
 * Marlin documents `U` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M702.html">MarlinFirmare M702 doc</a>
 */
data class UnloadFilament(
    /** `T` - extruder */
    val extruder: Int? = null,
    /** `Z` - distance */
    val distance: BigDecimal? = null,
    /** `U` - distance (required) */
    val u: BigDecimal? = null,
) : GRq<UnloadFilament> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (extruder != null) words.add(word('T', extruder))
        if (distance != null) words.add(word('Z', distance.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        return M(702, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<UnloadFilament> {

        override fun head(): GParameterWord<*> {
            return M(702).head
        }

        override fun decodeParams(params: List<GWord>): UnloadFilament {
            return UnloadFilament(
                extruder = params.intOf('T'),
                distance = params.decimalOf('Z'),
                u = params.decimalOf('U'),
            )
        }
    }
}
