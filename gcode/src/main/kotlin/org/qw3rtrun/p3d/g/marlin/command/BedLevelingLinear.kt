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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G29 [A<value>] [C<value>] [O] [Q<value>] [X<value>] [Y<value>] [P<value>] [S<rate>] [E<value>] [D<value>] [T<value>] [H<linear>] [F<linear>] [B<linear>] [L<linear>] [R<linear>] [J<value>] [V<value>]
 *
 * Bed Leveling (Linear) (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G29.html">MarlinFirmare G29 doc</a>
 */
data class BedLevelingLinear(
    /** `A` */
    val a: Boolean? = null,
    /** `C` */
    val c: Boolean? = null,
    /** `O` */
    val o: Boolean = false,
    /** `Q` */
    val q: Boolean? = null,
    /** `X` */
    val x: Int? = null,
    /** `Y` */
    val y: Int? = null,
    /** `P` */
    val p: Int? = null,
    /** `S` - rate */
    val rate: BigDecimal? = null,
    /** `E` */
    val e: Boolean? = null,
    /** `D` */
    val d: Boolean? = null,
    /** `T` */
    val t: Boolean? = null,
    /** `H` - linear */
    val linear: BigDecimal? = null,
    /** `F` - linear */
    val f: BigDecimal? = null,
    /** `B` - linear */
    val b: BigDecimal? = null,
    /** `L` - linear */
    val l: BigDecimal? = null,
    /** `R` - linear */
    val r: BigDecimal? = null,
    /** `J` */
    val j: Boolean? = null,
    /** `V` */
    val v: Int? = null,
) : GRq<BedLevelingLinear> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(18)
        if (a != null) words.add(word('A', if (a) 1 else 0))
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (o) words.add(flag('O'))
        if (q != null) words.add(word('Q', if (q) 1 else 0))
        if (x != null) words.add(word('X', x))
        if (y != null) words.add(word('Y', y))
        if (p != null) words.add(word('P', p))
        if (rate != null) words.add(word('S', rate.toPlainString()))
        if (e != null) words.add(word('E', if (e) 1 else 0))
        if (d != null) words.add(word('D', if (d) 1 else 0))
        if (t != null) words.add(word('T', if (t) 1 else 0))
        if (linear != null) words.add(word('H', linear.toPlainString()))
        if (f != null) words.add(word('F', f.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (j != null) words.add(word('J', if (j) 1 else 0))
        if (v != null) words.add(word('V', v))
        return G(29, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<BedLevelingLinear> {

        override fun head(): GParameterWord<*> {
            return G(29).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): BedLevelingLinear {
            val params = tokens.toList()
            return BedLevelingLinear(
                a = params.boolOf('A'),
                c = params.boolOf('C'),
                o = params.hasWord('O'),
                q = params.boolOf('Q'),
                x = params.intOf('X'),
                y = params.intOf('Y'),
                p = params.intOf('P'),
                rate = params.decimalOf('S'),
                e = params.boolOf('E'),
                d = params.boolOf('D'),
                t = params.boolOf('T'),
                linear = params.decimalOf('H'),
                f = params.decimalOf('F'),
                b = params.decimalOf('B'),
                l = params.decimalOf('L'),
                r = params.decimalOf('R'),
                j = params.boolOf('J'),
                v = params.intOf('V'),
            )
        }
    }
}
