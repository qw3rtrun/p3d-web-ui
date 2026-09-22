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
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M92 [X<steps>] [Y<steps>] [Z<steps>] [A<steps>] [B<steps>] [C<steps>] [U<steps>] [V<steps>] [W<steps>] [E<steps>] [T<index>]
 *
 * Set Axis Steps-per-unit (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M92.html">MarlinFirmare M92 doc</a>
 */
data class SetAxisStepsPerUnit(
    /** `X` - steps */
    val steps: BigDecimal? = null,
    /** `Y` - steps */
    val y: BigDecimal? = null,
    /** `Z` - steps */
    val z: BigDecimal? = null,
    /** `A` - steps */
    val a: BigDecimal? = null,
    /** `B` - steps */
    val b: BigDecimal? = null,
    /** `C` - steps */
    val c: BigDecimal? = null,
    /** `U` - steps */
    val u: BigDecimal? = null,
    /** `V` - steps */
    val v: BigDecimal? = null,
    /** `W` - steps */
    val w: BigDecimal? = null,
    /** `E` - steps */
    val e: BigDecimal? = null,
    /** `T` - index */
    val index: Int? = null,
) : GRq<SetAxisStepsPerUnit> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(11)
        if (steps != null) words.add(word('X', steps.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (index != null) words.add(word('T', index))
        return M(92, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetAxisStepsPerUnit> {

        override fun head(): GParameterWord<*> {
            return M(92).head
        }

        override fun decodeParams(params: List<GWord>): SetAxisStepsPerUnit {
            return SetAxisStepsPerUnit(
                steps = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                a = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                u = params.decimalOf('U'),
                v = params.decimalOf('V'),
                w = params.decimalOf('W'),
                e = params.decimalOf('E'),
                index = params.intOf('T'),
            )
        }
    }
}
