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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M306 [A<value>] [C<value>] [E<index>] [F<value>] [H<value>] [P<value>] [R<value>] [S<value>] [T]
 *
 * Model Predictive Temp. Control (thermal).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M306.html">MarlinFirmare M306 doc</a>
 */
data class ModelPredictiveTempControl(
    /** `A` - value */
    val value: BigDecimal? = null,
    /** `C` - value */
    val c: BigDecimal? = null,
    /** `E` - index */
    val index: Int? = null,
    /** `F` - value */
    val f: BigDecimal? = null,
    /** `H` - value */
    val h: BigDecimal? = null,
    /** `P` - value */
    val p: BigDecimal? = null,
    /** `R` - value */
    val r: BigDecimal? = null,
    /** `S` */
    val s: Int? = null,
    /** `T` */
    val t: Boolean = false,
) : GRq<ModelPredictiveTempControl> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(9)
        if (value != null) words.add(word('A', value.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (index != null) words.add(word('E', index))
        if (f != null) words.add(word('F', f.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (p != null) words.add(word('P', p.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (s != null) words.add(word('S', s))
        if (t) words.add(flag('T'))
        return M(306, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ModelPredictiveTempControl> {

        override fun head(): GParameterWord<*> {
            return M(306).head
        }

        override fun decodeParams(tokens: List<GToken>): ModelPredictiveTempControl {
            return ModelPredictiveTempControl(
                value = tokens.decimalOf('A'),
                c = tokens.decimalOf('C'),
                index = tokens.intOf('E'),
                f = tokens.decimalOf('F'),
                h = tokens.decimalOf('H'),
                p = tokens.decimalOf('P'),
                r = tokens.decimalOf('R'),
                s = tokens.intOf('S'),
                t = tokens.hasWord('T'),
            )
        }
    }
}
