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
 * M149 [C] [F] [K]
 *
 * Set Temperature Units (units).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M149.html">MarlinFirmare M149 doc</a>
 */
data class SetTemperatureUnits(
    /** `C` */
    val c: Boolean = false,
    /** `F` */
    val f: Boolean = false,
    /** `K` */
    val k: Boolean = false,
) : GRq<SetTemperatureUnits> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (c) words.add(flag('C'))
        if (f) words.add(flag('F'))
        if (k) words.add(flag('K'))
        return M(149, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetTemperatureUnits> {

        override fun head(): GParameterWord<*> {
            return M(149).head
        }

        override fun decodeParams(params: List<GWord>): SetTemperatureUnits {
            return SetTemperatureUnits(
                c = params.hasWord('C'),
                f = params.hasWord('F'),
                k = params.hasWord('K'),
            )
        }
    }
}
