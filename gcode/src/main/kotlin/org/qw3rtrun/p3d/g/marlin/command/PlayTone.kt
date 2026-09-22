// GENERATED FILE - do not edit.
//
// Regenerate with:  python3 tools/marlin/gen_mcommands.py
// Source of truth:  doc/marlin-gcode/commands.json
// Extracted from:   MarlinFirmware/MarlinDocumentation @ 0856d12b0253378bc8d17a246fb09fc8c5437997
// How and why:      tools/marlin/README.md, github.com/qw3rtrun/p3d-web-ui/issues/13

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
 * M300 [P<ms>] [S<value>]
 *
 * Play Tone (lcd).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M300.html">MarlinFirmare M300 doc</a>
 */
data class PlayTone(
    /** `P` - ms */
    val ms: Int? = null,
    /** `S` */
    val s: Int? = null,
) : GRq<PlayTone> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (ms != null) words.add(word('P', ms))
        if (s != null) words.add(word('S', s))
        return M(300, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<PlayTone> {

        override fun head(): GParameterWord<*> {
            return M(300).head
        }

        override fun decodeParams(params: List<GWord>): PlayTone {
            return PlayTone(
                ms = params.intOf('P'),
                s = params.intOf('S'),
            )
        }
    }
}
