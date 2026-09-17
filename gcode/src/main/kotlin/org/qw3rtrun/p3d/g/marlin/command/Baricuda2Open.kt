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
 * M128 [S<pressure>]
 *
 * Baricuda 2 Open (baricuda).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M128.html">MarlinFirmare M128 doc</a>
 */
data class Baricuda2Open(
    /** `S` - pressure */
    val pressure: Int? = null,
) : GRq<Baricuda2Open> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (pressure != null) words.add(word('S', pressure))
        return M(128, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<Baricuda2Open> {

        override fun head(): GParameterWord<*> {
            return M(128).head
        }

        override fun decodeParams(params: List<GWord>): Baricuda2Open {
            return Baricuda2Open(
                pressure = params.intOf('S'),
            )
        }
    }
}
