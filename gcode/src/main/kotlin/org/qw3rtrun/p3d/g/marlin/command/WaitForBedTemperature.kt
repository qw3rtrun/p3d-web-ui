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
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M190 [I<index>] [S<temp>] [R<temp>] [T<seconds>]
 *
 * Wait for Bed Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M190.html">MarlinFirmare M190 doc</a>
 */
data class WaitForBedTemperature(
    /** `I` - index */
    val index: Int? = null,
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `R` - temp */
    val r: BigDecimal? = null,
    /** `T` - seconds */
    val seconds: Int? = null,
) : GRq<WaitForBedTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (index != null) words.add(word('I', index))
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (seconds != null) words.add(word('T', seconds))
        return M(190, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<WaitForBedTemperature> {

        override fun head(): GParameterWord<*> {
            return M(190).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): WaitForBedTemperature {
            val params = tokens.toList()
            return WaitForBedTemperature(
                index = params.intOf('I'),
                temp = params.decimalOf('S'),
                r = params.decimalOf('R'),
                seconds = params.intOf('T'),
            )
        }
    }
}
