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
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M540 S<flag>
 *
 * Endstops Abort SD (sdcard).
 *
 * Marlin documents `S` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M540.html">MarlinFirmare M540 doc</a>
 */
data class EndstopsAbortSD(
    /** `S` - flag (required) */
    val flag: Boolean? = null,
) : GRq<EndstopsAbortSD> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (flag != null) words.add(word('S', if (flag) 1 else 0))
        return M(540, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<EndstopsAbortSD> {

        override fun head(): GParameterWord<*> {
            return M(540).head
        }

        override fun decodeParams(params: List<GWord>): EndstopsAbortSD {
            return EndstopsAbortSD(
                flag = params.boolOf('S'),
            )
        }
    }
}
