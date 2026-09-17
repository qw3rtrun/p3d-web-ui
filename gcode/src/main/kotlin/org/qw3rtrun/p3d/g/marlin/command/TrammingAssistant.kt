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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * G35 [S<value>]
 *
 * Tramming Assistant (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G35.html">MarlinFirmare G35 doc</a>
 */
data class TrammingAssistant(
    /** `S` */
    val s: Int? = null,
) : GRq<TrammingAssistant> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s != null) words.add(word('S', s))
        return G(35, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<TrammingAssistant> {

        override fun head(): GParameterWord<*> {
            return G(35).head
        }

        override fun decodeParams(params: List<GWord>): TrammingAssistant {
            return TrammingAssistant(
                s = params.intOf('S'),
            )
        }
    }
}
