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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M403 E<index> F<value>
 *
 * MMU2 Filament Type (control).
 *
 * Marlin documents `E`, `F` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M403.html">MarlinFirmare M403 doc</a>
 */
data class MMU2FilamentType(
    /** `E` - index (required) */
    val index: Int? = null,
    /** `F` (required) */
    val f: Int? = null,
) : GRq<MMU2FilamentType> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (index != null) words.add(word('E', index))
        if (f != null) words.add(word('F', f))
        return M(403, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<MMU2FilamentType> {

        override fun head(): GParameterWord<*> {
            return M(403).head
        }

        override fun decodeParams(params: List<GWord>): MMU2FilamentType {
            return MMU2FilamentType(
                index = params.intOf('E'),
                f = params.intOf('F'),
            )
        }
    }
}
