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
 * G26 [B<temp>] [C<value>] [D] [F<linear>] [H<linear>] [I<index>] [K<value>] [L<linear>] [O<linear>] [P<linear>] [Q<value>] [R<value>] [S<value>] [U<linear>] [X<linear>] [Y<linear>]
 *
 * Mesh Validation Pattern (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G26.html">MarlinFirmare G26 doc</a>
 */
data class MeshValidationPattern(
    /** `B` - temp */
    val temp: Int? = null,
    /** `C` */
    val c: Boolean? = null,
    /** `D` */
    val d: Boolean = false,
    /** `F` - linear */
    val linear: BigDecimal? = null,
    /** `H` - linear */
    val h: BigDecimal? = null,
    /** `I` - index */
    val index: Int? = null,
    /** `K` */
    val k: Boolean? = null,
    /** `L` - linear */
    val l: BigDecimal? = null,
    /** `O` - linear */
    val o: BigDecimal? = null,
    /** `P` - linear */
    val p: BigDecimal? = null,
    /** `Q` */
    val q: BigDecimal? = null,
    /** `R` */
    val r: Int? = null,
    /** `S` */
    val s: BigDecimal? = null,
    /** `U` - linear */
    val u: BigDecimal? = null,
    /** `X` - linear */
    val x: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
) : GRq<MeshValidationPattern> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(16)
        if (temp != null) words.add(word('B', temp))
        if (c != null) words.add(word('C', if (c) 1 else 0))
        if (d) words.add(flag('D'))
        if (linear != null) words.add(word('F', linear.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (index != null) words.add(word('I', index))
        if (k != null) words.add(word('K', if (k) 1 else 0))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (o != null) words.add(word('O', o.toPlainString()))
        if (p != null) words.add(word('P', p.toPlainString()))
        if (q != null) words.add(word('Q', q.toPlainString()))
        if (r != null) words.add(word('R', r))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (u != null) words.add(word('U', u.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        return G(26, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<MeshValidationPattern> {

        override fun head(): GParameterWord<*> {
            return G(26).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): MeshValidationPattern {
            val params = tokens.toList()
            return MeshValidationPattern(
                temp = params.intOf('B'),
                c = params.boolOf('C'),
                d = params.hasWord('D'),
                linear = params.decimalOf('F'),
                h = params.decimalOf('H'),
                index = params.intOf('I'),
                k = params.boolOf('K'),
                l = params.decimalOf('L'),
                o = params.decimalOf('O'),
                p = params.decimalOf('P'),
                q = params.decimalOf('Q'),
                r = params.intOf('R'),
                s = params.decimalOf('S'),
                u = params.decimalOf('U'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
            )
        }
    }
}
