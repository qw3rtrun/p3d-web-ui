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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M355 [P<value>] [S<value>]
 *
 * Case Light Control (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M355.html">MarlinFirmare M355 doc</a>
 */
data class CaseLightControl(
    /** `P` */
    val p: Int? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRq<CaseLightControl> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (p != null) words.add(word('P', p))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return M(355, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<CaseLightControl> {

        override fun head(): GParameterWord<*> {
            return M(355).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): CaseLightControl {
            val params = tokens.toList()
            return CaseLightControl(
                p = params.intOf('P'),
                s = params.boolOf('S'),
            )
        }
    }
}
