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
 * M32 [P<value>] [S<filepos>]
 *
 * Select and Start (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M32.html">MarlinFirmare M32 doc</a>
 */
data class SelectAndStart(
    /** `P` */
    val p: Int? = null,
    /** `S` - filepos */
    val filepos: Int? = null,
) : GRq<SelectAndStart> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (p != null) words.add(word('P', p))
        if (filepos != null) words.add(word('S', filepos))
        return M(32, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectAndStart> {

        override fun head(): GParameterWord<*> {
            return M(32).head
        }

        override fun decodeParams(params: List<GWord>): SelectAndStart {
            return SelectAndStart(
                p = params.intOf('P'),
                filepos = params.intOf('S'),
            )
        }
    }
}
