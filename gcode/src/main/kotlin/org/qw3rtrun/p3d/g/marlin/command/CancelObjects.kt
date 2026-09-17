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
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M486 [C] [P<index>] [S<index>] [T<count>] [U<index>]
 *
 * Cancel Objects (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M486.html">MarlinFirmare M486 doc</a>
 */
data class CancelObjects(
    /** `C` */
    val c: Boolean = false,
    /** `P` - index */
    val index: Int? = null,
    /** `S` - index */
    val s: Int? = null,
    /** `T` - count */
    val count: Int? = null,
    /** `U` - index */
    val u: Int? = null,
) : GRq<CancelObjects> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (c) words.add(flag('C'))
        if (index != null) words.add(word('P', index))
        if (s != null) words.add(word('S', s))
        if (count != null) words.add(word('T', count))
        if (u != null) words.add(word('U', u))
        return M(486, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<CancelObjects> {

        override fun head(): GParameterWord<*> {
            return M(486).head
        }

        override fun decodeParams(params: List<GWord>): CancelObjects {
            return CancelObjects(
                c = params.hasWord('C'),
                index = params.intOf('P'),
                s = params.intOf('S'),
                count = params.intOf('T'),
                u = params.intOf('U'),
            )
        }
    }
}
