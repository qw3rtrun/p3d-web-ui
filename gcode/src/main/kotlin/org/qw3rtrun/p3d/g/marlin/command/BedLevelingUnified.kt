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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G29 [A] [B<value>] [C<value>] [D] [E] [F<value>] [H<value>] [I<value>] [J<value>] [K<value>] [L<value>] [P<value>] [Q<value>] [R<value>] [S<slot>] [T<value>] [U] [V<value>] [W] [X<value>] [Y<value>]
 *
 * Bed Leveling (Unified) (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G29.html">MarlinFirmare G29 doc</a>
 */
data class BedLevelingUnified(
    /** `A` */
    val a: Boolean = false,
    /** `B` */
    val b: BigDecimal? = null,
    /** `C` */
    val c: BigDecimal? = null,
    /** `D` */
    val d: Boolean = false,
    /** `E` */
    val e: Boolean = false,
    /** `F` */
    val f: BigDecimal? = null,
    /** `H` */
    val h: BigDecimal? = null,
    /** `I` */
    val i: Int? = null,
    /** `J` */
    val j: Int? = null,
    /** `K` */
    val k: Int? = null,
    /** `L` */
    val l: Int? = null,
    /** `P` */
    val p: Int? = null,
    /** `Q` */
    val q: Int? = null,
    /** `R` */
    val r: Int? = null,
    /** `S` - slot */
    val slot: Int? = null,
    /** `T` */
    val t: Int? = null,
    /** `U` */
    val u: Boolean = false,
    /** `V` */
    val v: Int? = null,
    /** `W` */
    val w: Boolean = false,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
) : GRq<BedLevelingUnified> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(21)
        if (a) words.add(flag('A'))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (d) words.add(flag('D'))
        if (e) words.add(flag('E'))
        if (f != null) words.add(word('F', f.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (i != null) words.add(word('I', i))
        if (j != null) words.add(word('J', j))
        if (k != null) words.add(word('K', k))
        if (l != null) words.add(word('L', l))
        if (p != null) words.add(word('P', p))
        if (q != null) words.add(word('Q', q))
        if (r != null) words.add(word('R', r))
        if (slot != null) words.add(word('S', slot))
        if (t != null) words.add(word('T', t))
        if (u) words.add(flag('U'))
        if (v != null) words.add(word('V', v))
        if (w) words.add(flag('W'))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        return G(29, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<BedLevelingUnified> {

        override fun head(): GParameterWord<*> {
            return G(29).head
        }

        override fun decodeParams(params: List<GWord>): BedLevelingUnified {
            return BedLevelingUnified(
                a = params.hasWord('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                d = params.hasWord('D'),
                e = params.hasWord('E'),
                f = params.decimalOf('F'),
                h = params.decimalOf('H'),
                i = params.intOf('I'),
                j = params.intOf('J'),
                k = params.intOf('K'),
                l = params.intOf('L'),
                p = params.intOf('P'),
                q = params.intOf('Q'),
                r = params.intOf('R'),
                slot = params.intOf('S'),
                t = params.intOf('T'),
                u = params.hasWord('U'),
                v = params.intOf('V'),
                w = params.hasWord('W'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
            )
        }
    }
}
