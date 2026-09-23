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
 * M104 [I<index>] [S<temp>] [F<factor>] [B<temp>] [T<index>]
 *
 * Set Hotend Temperature (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M104.html">MarlinFirmare M104 doc</a>
 */
data class SetHotendTemperature(
    /** `I` - index */
    val index: Int? = null,
    /** `S` - temp */
    val temp: BigDecimal? = null,
    /** `F` - factor */
    val factor: BigDecimal? = null,
    /** `B` - temp */
    val b: BigDecimal? = null,
    /** `T` - index */
    val t: Int? = null,
) : GRq<SetHotendTemperature> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (index != null) words.add(word('I', index))
        if (temp != null) words.add(word('S', temp.toPlainString()))
        if (factor != null) words.add(word('F', factor.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (t != null) words.add(word('T', t))
        return M(104, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetHotendTemperature> {

        override fun head(): GParameterWord<*> {
            return M(104).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): SetHotendTemperature {
            val params = tokens.toList()
            return SetHotendTemperature(
                index = params.intOf('I'),
                temp = params.decimalOf('S'),
                factor = params.decimalOf('F'),
                b = params.decimalOf('B'),
                t = params.intOf('T'),
            )
        }
    }
}
