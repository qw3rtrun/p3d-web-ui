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
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G61 [F<rate>] [S<slot>] [X<value>] [Y<value>] [Z<value>] [E<value>]
 *
 * Return to Saved Position (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G61.html">MarlinFirmare G61 doc</a>
 */
data class ReturnToSavedPosition(
    /** `F` - rate */
    val rate: BigDecimal? = null,
    /** `S` - slot */
    val slot: Int? = null,
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `E` */
    val e: BigDecimal? = null,
) : GRq<ReturnToSavedPosition> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(6)
        if (rate != null) words.add(word('F', rate.toPlainString()))
        if (slot != null) words.add(word('S', slot))
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        return G(61, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ReturnToSavedPosition> {

        override fun head(): GParameterWord<*> {
            return G(61).head
        }

        override fun decodeParams(tokens: List<GToken>): ReturnToSavedPosition {
            return ReturnToSavedPosition(
                rate = tokens.decimalOf('F'),
                slot = tokens.intOf('S'),
                x = tokens.decimalOf('X'),
                y = tokens.decimalOf('Y'),
                z = tokens.decimalOf('Z'),
                e = tokens.decimalOf('E'),
            )
        }
    }
}
