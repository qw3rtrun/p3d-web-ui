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
 * M221 S<percent> [T<index>]
 *
 * Set Flow Percentage (motion).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M221.html">MarlinFirmare M221 doc</a>
 */
data class SetFlowPercentage(
    /** `S` - percent (required) */
    val percent: Int? = null,
    /** `T` - index */
    val index: Int? = null,
) : GRq<SetFlowPercentage> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (percent != null) words.add(word('S', percent))
        if (index != null) words.add(word('T', index))
        return M(221, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetFlowPercentage> {

        override fun head(): GParameterWord<*> {
            return M(221).head
        }

        override fun decodeParams(params: List<GWord>): SetFlowPercentage {
            return SetFlowPercentage(
                percent = params.intOf('S'),
                index = params.intOf('T'),
            )
        }
    }
}
