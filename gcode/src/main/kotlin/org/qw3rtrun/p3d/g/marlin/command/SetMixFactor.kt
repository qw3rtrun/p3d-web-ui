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
 * M163 [S<index>] [P<factor>]
 *
 * Set Mix Factor (mixing).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M163.html">MarlinFirmare M163 doc</a>
 */
data class SetMixFactor(
    /** `S` - index */
    val index: Int? = null,
    /** `P` - factor */
    val factor: BigDecimal? = null,
) : GRq<SetMixFactor> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (index != null) words.add(word('S', index))
        if (factor != null) words.add(word('P', factor.toPlainString()))
        return M(163, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetMixFactor> {

        override fun head(): GParameterWord<*> {
            return M(163).head
        }

        override fun decodeParams(params: List<GWord>): SetMixFactor {
            return SetMixFactor(
                index = params.intOf('S'),
                factor = params.decimalOf('P'),
            )
        }
    }
}
