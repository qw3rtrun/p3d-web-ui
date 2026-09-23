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
 * M907 [B<current>] [C<current>] [D<current>] [E<current>] [S<current>] [X<current>] [Y<current>] [Z<current>] [I<current>] [J<current>] [K<current>] [U<current>] [V<current>] [W<current>]
 *
 * Trimpot Stepper Motor Current (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M907.html">MarlinFirmare M907 doc</a>
 */
data class TrimpotStepperMotorCurrent(
    /** `B` - current */
    val current: BigDecimal? = null,
    /** `C` - current */
    val c: BigDecimal? = null,
    /** `D` - current */
    val d: BigDecimal? = null,
    /** `E` - current */
    val e: BigDecimal? = null,
    /** `S` - current */
    val s: BigDecimal? = null,
    /** `X` - current */
    val x: BigDecimal? = null,
    /** `Y` - current */
    val y: BigDecimal? = null,
    /** `Z` - current */
    val z: BigDecimal? = null,
    /** `I` - current */
    val i: BigDecimal? = null,
    /** `J` - current */
    val j: BigDecimal? = null,
    /** `K` - current */
    val k: BigDecimal? = null,
    /** `U` - current */
    val u: BigDecimal? = null,
    /** `V` - current */
    val v: BigDecimal? = null,
    /** `W` - current */
    val w: BigDecimal? = null,
) : GRq<TrimpotStepperMotorCurrent> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(14)
        if (current != null) words.add(word('B', current.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (k != null) words.add(word('K', k.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        return M(907, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<TrimpotStepperMotorCurrent> {

        override fun head(): GParameterWord<*> {
            return M(907).head
        }

        override fun decodeParams(tokens: List<GToken>): TrimpotStepperMotorCurrent {
            return TrimpotStepperMotorCurrent(
                current = tokens.decimalOf('B'),
                c = tokens.decimalOf('C'),
                d = tokens.decimalOf('D'),
                e = tokens.decimalOf('E'),
                s = tokens.decimalOf('S'),
                x = tokens.decimalOf('X'),
                y = tokens.decimalOf('Y'),
                z = tokens.decimalOf('Z'),
                i = tokens.decimalOf('I'),
                j = tokens.decimalOf('J'),
                k = tokens.decimalOf('K'),
                u = tokens.decimalOf('U'),
                v = tokens.decimalOf('V'),
                w = tokens.decimalOf('W'),
            )
        }
    }
}
