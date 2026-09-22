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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M423 [R] [A<linear>] [E<linear>] [I<linear>] [X<index>] [Z<linear>]
 *
 * X Twist Compensation (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M423.html">MarlinFirmare M423 doc</a>
 */
data class XTwistCompensation(
    /** `R` */
    val r: Boolean = false,
    /** `A` - linear */
    val linear: BigDecimal? = null,
    /** `E` - linear */
    val e: BigDecimal? = null,
    /** `I` - linear */
    val i: BigDecimal? = null,
    /** `X` - index */
    val index: Int? = null,
    /** `Z` - linear */
    val z: BigDecimal? = null,
) : GRq<XTwistCompensation> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (r) words.add(flag('R'))
        if (linear != null) words.add(word('A', linear.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (index != null) words.add(word('X', index))
        if (z != null) words.add(word('Z', z.toPlainString()))
        return M(423, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<XTwistCompensation> {

        override fun head(): GParameterWord<*> {
            return M(423).head
        }

        override fun decodeParams(params: List<GWord>): XTwistCompensation {
            return XTwistCompensation(
                r = params.hasWord('R'),
                linear = params.decimalOf('A'),
                e = params.decimalOf('E'),
                i = params.decimalOf('I'),
                index = params.intOf('X'),
                z = params.decimalOf('Z'),
            )
        }
    }
}
