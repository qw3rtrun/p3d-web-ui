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
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M166 A<linear> Z<linear> I<index> J<index> [S<enable>] [T<index>]
 *
 * Gradient Mix (mixing).
 *
 * Marlin documents `A`, `Z`, `I`, `J` as required; every property here still defaults to
 * absent, so that every command class is constructible bare.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M166.html">MarlinFirmare M166 doc</a>
 */
data class GradientMix(
    /** `A` - linear (required) */
    val linear: BigDecimal? = null,
    /** `Z` - linear (required) */
    val z: BigDecimal? = null,
    /** `I` - index (required) */
    val index: Int? = null,
    /** `J` - index (required) */
    val j: Int? = null,
    /** `S` - enable */
    val enable: Boolean? = null,
    /** `T` - index */
    val t: Int? = null,
) : GRq<GradientMix> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (linear != null) words.add(word('A', linear.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (index != null) words.add(word('I', index))
        if (j != null) words.add(word('J', j))
        if (enable != null) words.add(word('S', if (enable) 1 else 0))
        if (t != null) words.add(word('T', t))
        return M(166, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<GradientMix> {

        override fun head(): GParameterWord<*> {
            return M(166).head
        }

        override fun decodeParams(params: List<GWord>): GradientMix {
            return GradientMix(
                linear = params.decimalOf('A'),
                z = params.decimalOf('Z'),
                index = params.intOf('I'),
                j = params.intOf('J'),
                enable = params.boolOf('S'),
                t = params.intOf('T'),
            )
        }
    }
}
