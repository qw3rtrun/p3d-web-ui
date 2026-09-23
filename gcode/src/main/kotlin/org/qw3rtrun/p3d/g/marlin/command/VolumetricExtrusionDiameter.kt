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
 * M200 [D<diameter>] [L<volume>] [S<value>] [T<index>]
 *
 * Volumetric Extrusion Diameter (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M200.html">MarlinFirmare M200 doc</a>
 */
data class VolumetricExtrusionDiameter(
    /** `D` - diameter */
    val diameter: BigDecimal? = null,
    /** `L` - volume */
    val volume: BigDecimal? = null,
    /** `S` */
    val s: Int? = null,
    /** `T` - index */
    val index: Int? = null,
) : GRq<VolumetricExtrusionDiameter> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (diameter != null) words.add(word('D', diameter.toPlainString()))
        if (volume != null) words.add(word('L', volume.toPlainString()))
        if (s != null) words.add(word('S', s))
        if (index != null) words.add(word('T', index))
        return M(200, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<VolumetricExtrusionDiameter> {

        override fun head(): GParameterWord<*> {
            return M(200).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): VolumetricExtrusionDiameter {
            val params = tokens.toList()
            return VolumetricExtrusionDiameter(
                diameter = params.decimalOf('D'),
                volume = params.decimalOf('L'),
                s = params.intOf('S'),
                index = params.intOf('T'),
            )
        }
    }
}
