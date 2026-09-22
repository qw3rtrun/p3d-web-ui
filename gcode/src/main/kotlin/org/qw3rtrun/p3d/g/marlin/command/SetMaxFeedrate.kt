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
 * M203 [X<value>] [Y<value>] [Z<value>] [E<value>] [T<index>]
 *
 * Set Max Feedrate (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M203.html">MarlinFirmare M203 doc</a>
 */
data class SetMaxFeedrate(
    /** `X` */
    val x: BigDecimal? = null,
    /** `Y` */
    val y: BigDecimal? = null,
    /** `Z` */
    val z: BigDecimal? = null,
    /** `E` */
    val e: BigDecimal? = null,
    /** `T` - index */
    val index: Int? = null,
) : GRq<SetMaxFeedrate> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (x != null) words.add(word('X', x.toPlainString()))
        if (y != null) words.add(word('Y', y.toPlainString()))
        if (z != null) words.add(word('Z', z.toPlainString()))
        if (e != null) words.add(word('E', e.toPlainString()))
        if (index != null) words.add(word('T', index))
        return M(203, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetMaxFeedrate> {

        override fun head(): GParameterWord<*> {
            return M(203).head
        }

        override fun decodeParams(params: List<GWord>): SetMaxFeedrate {
            return SetMaxFeedrate(
                x = params.decimalOf('X'),
                y = params.decimalOf('Y'),
                z = params.decimalOf('Z'),
                e = params.decimalOf('E'),
                index = params.intOf('T'),
            )
        }
    }
}
