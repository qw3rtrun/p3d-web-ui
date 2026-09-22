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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G38.4 [X<pos>] [Y<pos>] [Z<pos>] [F<rate>]
 *
 * Probe target (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G38-4.html">MarlinFirmare G38.4 doc</a>
 */
data class ProbeTargetG38_4(
    /** `X` - pos */
    val pos: BigDecimal? = null,
    /** `Y` - pos */
    val y: BigDecimal? = null,
    /** `Z` - pos */
    val z: BigDecimal? = null,
    /** `F` - rate */
    val rate: BigDecimal? = null,
) : GRq<ProbeTargetG38_4> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (pos != null) words.add(word('X', pos.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (rate != null) words.add(word('F', rate.toPlainString()))
        return G("38.4", *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ProbeTargetG38_4> {

        override fun head(): GParameterWord<*> {
            return G("38.4").head
        }

        override fun decodeParams(params: List<GWord>): ProbeTargetG38_4 {
            return ProbeTargetG38_4(
                pos = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                rate = params.decimalOf('F'),
            )
        }
    }
}
