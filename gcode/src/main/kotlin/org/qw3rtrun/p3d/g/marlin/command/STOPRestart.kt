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
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M999 [S<value>]
 *
 * STOP Restart (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M999.html">MarlinFirmare M999 doc</a>
 */
data class STOPRestart(
    /** `S` */
    val s: Boolean? = null,
) : GRq<STOPRestart> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return M(999, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<STOPRestart> {

        override fun head(): GParameterWord<*> {
            return M(999).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): STOPRestart {
            val params = tokens.toList()
            return STOPRestart(
                s = params.boolOf('S'),
            )
        }
    }
}
