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
 * M34 [S<value>] [F<value>]
 *
 * SDCard Sorting (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M34.html">MarlinFirmare M34 doc</a>
 */
data class SDCardSorting(
    /** `S` */
    val s: BigDecimal? = null,
    /** `F` */
    val f: Int? = null,
) : GRq<SDCardSorting> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (s != null) words.add(word('S', s.toPlainString()))
        if (f != null) words.add(word('F', f))
        return M(34, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SDCardSorting> {

        override fun head(): GParameterWord<*> {
            return M(34).head
        }

        override fun decodeParams(params: List<GWord>): SDCardSorting {
            return SDCardSorting(
                s = params.decimalOf('S'),
                f = params.intOf('F'),
            )
        }
    }
}
