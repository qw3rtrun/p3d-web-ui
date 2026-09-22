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
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M430 [I<value>] [V<value>] [W<value>]
 *
 * Power Monitor (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M430.html">MarlinFirmare M430 doc</a>
 */
data class PowerMonitor(
    /** `I` */
    val i: Boolean? = null,
    /** `V` */
    val v: Boolean? = null,
    /** `W` */
    val w: Boolean? = null,
) : GRq<PowerMonitor> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (i != null) words.add(word('I', if (i) 1 else 0))
        if (v != null) words.add(word('V', if (v) 1 else 0))
        if (w != null) words.add(word('W', if (w) 1 else 0))
        return M(430, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<PowerMonitor> {

        override fun head(): GParameterWord<*> {
            return M(430).head
        }

        override fun decodeParams(params: List<GWord>): PowerMonitor {
            return PowerMonitor(
                i = params.boolOf('I'),
                v = params.boolOf('V'),
                w = params.boolOf('W'),
            )
        }
    }
}
