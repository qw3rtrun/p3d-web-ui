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
 * M165 [A<factor>] [B<factor>] [C<factor>] [D<factor>] [H<factor>] [I<factor>]
 *
 * Set Mix (mixing).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M165.html">MarlinFirmare M165 doc</a>
 */
data class SetMix(
    /** `A` - factor */
    val factor: BigDecimal? = null,
    /** `B` - factor */
    val b: BigDecimal? = null,
    /** `C` - factor */
    val c: BigDecimal? = null,
    /** `D` - factor */
    val d: BigDecimal? = null,
    /** `H` - factor */
    val h: BigDecimal? = null,
    /** `I` - factor */
    val i: BigDecimal? = null,
) : GRq<SetMix> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (factor != null) words.add(word('A', factor.toPlainString()))
        if (b != null) words.add(word('B', b.toPlainString()))
        if (c != null) words.add(word('C', c.toPlainString()))
        if (d != null) words.add(word('D', d.toPlainString()))
        if (h != null) words.add(word('H', h.toPlainString()))
        if (i != null) words.add(word('I', i.toPlainString()))
        return M(165, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetMix> {

        override fun head(): GParameterWord<*> {
            return M(165).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): SetMix {
            val params = tokens.toList()
            return SetMix(
                factor = params.decimalOf('A'),
                b = params.decimalOf('B'),
                c = params.decimalOf('C'),
                d = params.decimalOf('D'),
                h = params.decimalOf('H'),
                i = params.decimalOf('I'),
            )
        }
    }
}
