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
 * M701 [T<extruder>] [Z<distance>] L<distance>
 *
 * Load filament (control).
 *
 * Marlin documents `L` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M701.html">MarlinFirmare M701 doc</a>
 */
data class LoadFilament(
    /** `T` - extruder */
    val extruder: Int? = null,
    /** `Z` - distance */
    val distance: BigDecimal? = null,
    /** `L` - distance (required) */
    val l: BigDecimal? = null,
) : GRq<LoadFilament> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (extruder != null) words.add(word('T', extruder))
        if (distance != null) words.add(word('Z', distance.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        return M(701, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<LoadFilament> {

        override fun head(): GParameterWord<*> {
            return M(701).head
        }

        override fun decodeParams(params: List<GWord>): LoadFilament {
            return LoadFilament(
                extruder = params.intOf('T'),
                distance = params.decimalOf('Z'),
                l = params.decimalOf('L'),
            )
        }
    }
}
