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
import org.qw3rtrun.p3d.g.code.core.token.GToken
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * G27 [P<value>]
 *
 * Park toolhead (nozzle).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G27.html">MarlinFirmare G27 doc</a>
 */
data class ParkToolhead(
    /** `P` */
    val p: Int? = null,
) : GRq<ParkToolhead> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (p != null) words.add(word('P', p))
        return G(27, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ParkToolhead> {

        override fun head(): GParameterWord<*> {
            return G(27).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): ParkToolhead {
            val params = tokens.toList()
            return ParkToolhead(
                p = params.intOf('P'),
            )
        }
    }
}
