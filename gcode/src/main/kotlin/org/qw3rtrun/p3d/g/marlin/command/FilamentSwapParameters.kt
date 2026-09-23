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
 * M217 [Q] [S<linear>] [B<linear>] [E<linear>] [P<feedrate>] [R<feedrate>] [U<linear>] [F<linear>] [G<linear>] [A<linear>] [L<linear>] [W<linear>] [X<linear>] [Y<linear>] [V<linear>] [Z<feedrate>] [I<linear>] [J<linear>] [K<linear>] [C<linear>] [H<linear>] [O<linear>]
 *
 * Filament swap parameters (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M217.html">MarlinFirmare M217 doc</a>
 */
data class FilamentSwapParameters(
    /** `Q` */
    val q: Boolean = false,
    /** `S` - linear */
    val linear: BigDecimal? = null,
    /** `B` - linear */
    val b: BigDecimal? = null,
    /** `E` - linear */
    val e: BigDecimal? = null,
    /** `P` - feedrate */
    val feedrate: Int? = null,
    /** `R` - feedrate */
    val r: Int? = null,
    /** `U` - linear */
    val u: Int? = null,
    /** `F` - linear */
    val f: Int? = null,
    /** `G` - linear */
    val g: Int? = null,
    /** `A` - linear */
    val a: Int? = null,
    /** `L` - linear */
    val l: Int? = null,
    /** `W` - linear */
    val w: Int? = null,
    /** `X` - linear */
    val x: BigDecimal? = null,
    /** `Y` - linear */
    val y: BigDecimal? = null,
    /** `V` - linear */
    val v: Int? = null,
    /** `Z` - feedrate */
    val z: Int? = null,
    /** `I` - linear */
    val i: BigDecimal? = null,
    /** `J` - linear */
    val j: BigDecimal? = null,
    /** `K` - linear */
    val k: BigDecimal? = null,
    /** `C` - linear */
    val c: BigDecimal? = null,
    /** `H` - linear */
    val h: BigDecimal? = null,
    /** `O` - linear */
    val o: BigDecimal? = null,
) : GRq<FilamentSwapParameters> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(22)
        if (q) words.add(flag('Q'))
        if (linear != null) words.add(word('S', linear.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (feedrate != null) words.add(word('P', feedrate))
        if (r != null) words.add(word('R', r))
        if (u != null) words.add(word('U', u))
        if (f != null) words.add(word('F', f))
        if (g != null) words.add(word('G', g))
        if (a != null) words.add(word('A', a))
        if (l != null) words.add(word('L', l))
        if (w != null) words.add(word('W', w))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (v != null) words.add(word('V', v))
        if (z != null) words.add(word('Z', z))
        if (i != null) words.add(word('I', i.toPlainString()))
        if (j != null) words.add(word('J', j.toPlainString()))
        if (k != null) words.add(word('K', k.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (o != null) words.add(word('O', o.toPlainString()))
        return M(217, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<FilamentSwapParameters> {

        override fun head(): GParameterWord<*> {
            return M(217).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): FilamentSwapParameters {
            val params = tokens.toList()
            return FilamentSwapParameters(
                q = params.hasWord('Q'),
                linear = params.decimalOf('S'),
                b = params.decimalOf('B'),
                e = params.decimalOf('E'),
                feedrate = params.intOf('P'),
                r = params.intOf('R'),
                u = params.intOf('U'),
                f = params.intOf('F'),
                g = params.intOf('G'),
                a = params.intOf('A'),
                l = params.intOf('L'),
                w = params.intOf('W'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                v = params.intOf('V'),
                z = params.intOf('Z'),
                i = params.decimalOf('I'),
                j = params.decimalOf('J'),
                k = params.decimalOf('K'),
                c = params.decimalOf('C'),
                h = params.decimalOf('H'),
                o = params.decimalOf('O'),
            )
        }
    }
}
