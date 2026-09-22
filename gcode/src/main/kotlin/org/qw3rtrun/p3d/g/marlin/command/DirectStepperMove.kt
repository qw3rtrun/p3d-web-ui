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
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G6 [I<index>] [R<rate>] [S<rate>] [X<direction>] [Y<direction>] [Z<direction>] [E<direction>]
 *
 * Direct Stepper Move (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G6.html">MarlinFirmare G6 doc</a>
 */
data class DirectStepperMove(
    /** `I` - index */
    val index: Int? = null,
    /** `R` - rate */
    val rate: BigDecimal? = null,
    /** `S` - rate */
    val s: BigDecimal? = null,
    /** `X` - direction */
    val direction: Int? = null,
    /** `Y` - direction */
    val y: Int? = null,
    /** `Z` - direction */
    val z: Int? = null,
    /** `E` - direction */
    val e: Int? = null,
) : GRq<DirectStepperMove> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(7)
        if (index != null) words.add(word('I', index))
        if (rate != null) words.add(word('R', rate.toPlainString()))
        if (s != null) words.add(word('S', s.toPlainString()))
        if (direction != null) words.add(word('X', direction))
        if (y != null) words.add(word('Y', y))
        if (z != null) words.add(word('Z', z))
        if (e != null) words.add(word('E', e))
        return G(6, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<DirectStepperMove> {

        override fun head(): GParameterWord<*> {
            return G(6).head
        }

        override fun decodeParams(params: List<GWord>): DirectStepperMove {
            return DirectStepperMove(
                index = params.intOf('I'),
                rate = params.decimalOf('R'),
                s = params.decimalOf('S'),
                direction = params.intOf('X'),
                y = params.intOf('Y'),
                z = params.intOf('Z'),
                e = params.intOf('E'),
            )
        }
    }
}
