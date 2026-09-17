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
 * M900 [K<kfactor>] [L<kfactor>] [S<slot>] [T<index>]
 *
 * Linear Advance Factor (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M900.html">MarlinFirmare M900 doc</a>
 */
data class LinearAdvanceFactor(
    /** `K` - kfactor */
    val kfactor: BigDecimal? = null,
    /** `L` - kfactor */
    val l: BigDecimal? = null,
    /** `S` - slot */
    val slot: Int? = null,
    /** `T` - index */
    val index: Int? = null,
) : GRq<LinearAdvanceFactor> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (kfactor != null) words.add(word('K', kfactor.toPlainString()))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (slot != null) words.add(word('S', slot))
        if (index != null) words.add(word('T', index))
        return M(900, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<LinearAdvanceFactor> {

        override fun head(): GParameterWord<*> {
            return M(900).head
        }

        override fun decodeParams(params: List<GWord>): LinearAdvanceFactor {
            return LinearAdvanceFactor(
                kfactor = params.decimalOf('K'),
                l = params.decimalOf('L'),
                slot = params.intOf('S'),
                index = params.intOf('T'),
            )
        }
    }
}
