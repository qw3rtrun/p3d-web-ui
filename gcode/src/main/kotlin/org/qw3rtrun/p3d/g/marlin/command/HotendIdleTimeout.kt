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
 * M86 [S<seconds>] [T<temp>] [E<temp>] [B<temp>]
 *
 * Hotend Idle Timeout (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M86.html">MarlinFirmare M86 doc</a>
 */
data class HotendIdleTimeout(
    /** `S` - seconds */
    val seconds: Int? = null,
    /** `T` - temp */
    val temp: Int? = null,
    /** `E` - temp */
    val e: Int? = null,
    /** `B` - temp */
    val b: Int? = null,
) : GRq<HotendIdleTimeout> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (seconds != null) words.add(word('S', seconds))
        if (temp != null) words.add(word('T', temp))
        if (e != null) words.add(word('E', e))
        if (b != null) words.add(word('B', b))
        return M(86, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<HotendIdleTimeout> {

        override fun head(): GParameterWord<*> {
            return M(86).head
        }

        override fun decodeParams(params: List<GWord>): HotendIdleTimeout {
            return HotendIdleTimeout(
                seconds = params.intOf('S'),
                temp = params.intOf('T'),
                e = params.intOf('E'),
                b = params.intOf('B'),
            )
        }
    }
}
