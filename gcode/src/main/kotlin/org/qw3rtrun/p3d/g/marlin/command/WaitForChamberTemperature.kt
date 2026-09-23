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
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M191 [S<temp>] [R<temp>]
 *
 * Wait for Chamber Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M191.html">MarlinFirmare M191 doc</a>
 */
data class WaitForChamberTemperature(
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `R` - temp */
    val r: BigDecimal? = null,
) : GRq<WaitForChamberTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        return M(191, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<WaitForChamberTemperature> {

        override fun head(): GParameterWord<*> {
            return M(191).head
        }

        override fun decodeParams(tokens: List<GToken>): WaitForChamberTemperature {
            return WaitForChamberTemperature(
                temp = tokens.decimalOf('S'),
                r = tokens.decimalOf('R'),
            )
        }
    }
}
