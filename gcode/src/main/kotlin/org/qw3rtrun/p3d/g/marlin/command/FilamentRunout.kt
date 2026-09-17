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
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M412 [D<linear>] [H<value>] [L<linear>] [S<value>] [R<value>]
 *
 * Filament Runout (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M412.html">MarlinFirmare M412 doc</a>
 */
data class FilamentRunout(
    /** `D` - linear */
    val linear: BigDecimal? = null,
    /** `H` */
    val h: Boolean? = null,
    /** `L` - linear */
    val l: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
    /** `R` */
    val r: Boolean? = null,
) : GRq<FilamentRunout> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(5)
        if (linear != null) words.add(word('D', linear.toPlainString()))
        if (h != null) words.add(word('H', if (h) 1 else 0))
        if (l != null) words.add(word('L', l.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        if (r != null) words.add(word('R', if (r) 1 else 0))
        return M(412, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<FilamentRunout> {

        override fun head(): GParameterWord<*> {
            return M(412).head
        }

        override fun decodeParams(params: List<GWord>): FilamentRunout {
            return FilamentRunout(
                linear = params.decimalOf('D'),
                h = params.boolOf('H'),
                l = params.decimalOf('L'),
                s = params.boolOf('S'),
                r = params.boolOf('R'),
            )
        }
    }
}
