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
 * M665 [H<linear>] [L<linear>] [R<linear>] [S<value>] [X<value>] [Y<value>] [Z<value>] [A<value>] [B<value>] [C<value>]
 *
 * Delta Configuration.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M665.html">MarlinFirmare M665 doc</a>
 */
data class DeltaConfiguration(
    /** `H` - linear */
    val linear: BigDecimal? = null,
    /** `L` - linear */
    val l: BigDecimal? = null,
    /** `R` - linear */
    val r: BigDecimal? = null,
    /** `S` */
    val s: BigDecimal? = null,
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
) : GRq<DeltaConfiguration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(10)
        if (linear != null) words.add(word('H', linear.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (r != null) words.add(word('R', r.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (a != null) words.add(word('A', a.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        return M(665, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<DeltaConfiguration> {

        override fun head(): GParameterWord<*> {
            return M(665).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): DeltaConfiguration {
            val params = tokens.toList()
            return DeltaConfiguration(
                linear = params.decimalOf('H'),
                l = params.decimalOf('L'),
                r = params.decimalOf('R'),
                s = params.decimalOf('S'),
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                a = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
            )
        }
    }
}
