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
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M915 [S<value>] [Z<value>]
 *
 * TMC Z axis calibration (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M915.html">MarlinFirmare M915 doc</a>
 */
data class TMCZAxisCalibration(
    /** `S` */
    val s: Int? = null,
    /** `Z` */
    val z: BigDecimal? = null,
) : GRq<TMCZAxisCalibration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (s != null) words.add(word('S', s))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(915, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<TMCZAxisCalibration> {

        override fun head(): GParameterWord<*> {
            return M(915).head
        }

        override fun decodeParams(params: List<GWord>): TMCZAxisCalibration {
            return TMCZAxisCalibration(
                s = params.intOf('S'),
                z = params.decimalOf('Z'),
            )
        }
    }
}
