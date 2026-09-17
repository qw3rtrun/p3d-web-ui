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
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M401 [H] [S<value>] [R<value>]
 *
 * Deploy Probe (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M401.html">MarlinFirmare M401 doc</a>
 */
data class DeployProbe(
    /** `H` */
    val h: Boolean = false,
    /** `S` */
    val s: Boolean? = null,
    /** `R` */
    val r: Boolean? = null,
) : GRq<DeployProbe> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (h) words.add(flag('H'))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        if (r != null) words.add(word('R', if (r) 1 else 0))
        return M(401, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<DeployProbe> {

        override fun head(): GParameterWord<*> {
            return M(401).head
        }

        override fun decodeParams(params: List<GWord>): DeployProbe {
            return DeployProbe(
                h = params.hasWord('H'),
                s = params.boolOf('S'),
                r = params.boolOf('R'),
            )
        }
    }
}
