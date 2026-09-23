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
 * M109 [I<index>] [S<temp>] [R<temp>] [F<factor>] [B<temp>] [T<index>]
 *
 * Wait for Hotend Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M109.html">MarlinFirmare M109 doc</a>
 */
data class WaitForHotendTemperature(
    /** `I` - index */
    val index: Int? = null,
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `R` - temp */
    val r: BigDecimal? = null,
    /** `F` - factor */
    val factor: BigDecimal? = null,
    /** `B` - temp */
    val b: BigDecimal? = null,
    /** `T` - index */
    val t: Int? = null,
) : GRq<WaitForHotendTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (index != null) words.add(word('I', index))
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (factor != null) words.add(word('F', factor.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (t != null) words.add(word('T', t))
        return M(109, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<WaitForHotendTemperature> {

        override fun head(): GParameterWord<*> {
            return M(109).head
        }

        override fun decodeParams(tokens: List<GToken>): WaitForHotendTemperature {
            return WaitForHotendTemperature(
                index = tokens.intOf('I'),
                temp = tokens.decimalOf('S'),
                r = tokens.decimalOf('R'),
                factor = tokens.decimalOf('F'),
                b = tokens.decimalOf('B'),
                t = tokens.intOf('T'),
            )
        }
    }
}
