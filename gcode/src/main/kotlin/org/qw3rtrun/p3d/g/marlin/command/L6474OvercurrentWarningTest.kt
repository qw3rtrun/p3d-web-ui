// GENERATED FILE - do not edit.
//
// Regenerate with:  python3 tools/marlin/gen_mcommands.py
// Source of truth:  doc/marlin-gcode/commands.json
// Extracted from:   MarlinFirmware/MarlinDocumentation @ 0856d12b0253378bc8d17a246fb09fc8c5437997
// How and why:      tools/marlin/README.md, doc/todos/11-marlin-commands.md

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
 * M917 [J<value>] [X<value>] [Y<value>] [Z<value>] [A<value>] [B<value>] [C<value>] [U<value>] [V<value>] [W<value>] [E<value>] [F<feedrate>] [I<current>] [T<current>] [K<value>]
 *
 * L6474 Overcurrent Warning Test (L6474).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M917.html">MarlinFirmare M917 doc</a>
 */
data class L6474OvercurrentWarningTest(
    /** `J` */
    val j: Int? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `A` */
    val a: BigDecimal? = null,
    /** `B` */
    val b: BigDecimal? = null,
    /** `C` */
    val c: BigDecimal? = null,
    /** `U` */
    val u: BigDecimal? = null,
    /** `V` */
    val v: BigDecimal? = null,
    /** `W` */
    val w: BigDecimal? = null,
    /** `E` */
    val e: BigDecimal? = null,
    /** `F` - feedrate */
    val feedrate: Int? = null,
    /** `I` - current */
    val current: Int? = null,
    /** `T` - current */
    val t: Int? = null,
    /** `K` */
    val k: Int? = null,
) : GRq<L6474OvercurrentWarningTest> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(15)
        if (j != null) words.add(word('J', j))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (v != null) words.add(word('V', v.toPlainString()))
        if (w != null) words.add(word('W', w.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (feedrate != null) words.add(word('F', feedrate))
        if (current != null) words.add(word('I', current))
        if (t != null) words.add(word('T', t))
        if (k != null) words.add(word('K', k))
        return M(917, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<L6474OvercurrentWarningTest> {

        override fun head(): GParameterWord<*> {
            return M(917).head
        }

        override fun decodeParams(params: List<GWord>): L6474OvercurrentWarningTest {
            return L6474OvercurrentWarningTest(
                j = params.intOf('J'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                a = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                u = params.decimalOf('U'),
                v = params.decimalOf('V'),
                w = params.decimalOf('W'),
                e = params.decimalOf('E'),
                feedrate = params.intOf('F'),
                current = params.intOf('I'),
                t = params.intOf('T'),
                k = params.intOf('K'),
            )
        }
    }
}
