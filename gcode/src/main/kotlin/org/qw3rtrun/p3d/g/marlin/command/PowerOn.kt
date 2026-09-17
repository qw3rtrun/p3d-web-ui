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
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M80 [S]
 *
 * Power On (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M80.html">MarlinFirmare M80 doc</a>
 */
data class PowerOn(
    /** `S` */
    val s: Boolean = false,
) : GRq<PowerOn> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s) words.add(flag('S'))
        return M(80, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<PowerOn> {

        override fun head(): GParameterWord<*> {
            return M(80).head
        }

        override fun decodeParams(params: List<GWord>): PowerOn {
            return PowerOn(
                s = params.hasWord('S'),
            )
        }
    }
}
