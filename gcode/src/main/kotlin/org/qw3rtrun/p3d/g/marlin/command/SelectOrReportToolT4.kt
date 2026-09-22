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
import org.qw3rtrun.p3d.g.code.dsl.T
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.boolOf
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * T4 [F<feedrate>] [S<value>]
 *
 * Select or Report Tool (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/T4.html">MarlinFirmare T4 doc</a>
 */
data class SelectOrReportToolT4(
    /** `F` - feedrate */
    val feedrate: BigDecimal? = null,
    /** `S` */
    val s: Boolean? = null,
) : GRq<SelectOrReportToolT4> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (feedrate != null) words.add(word('F', feedrate.toPlainString()))
        if (s != null) words.add(word('S', if (s) 1 else 0))
        return T(4, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectOrReportToolT4> {

        override fun head(): GParameterWord<*> {
            return T(4).head
        }

        override fun decodeParams(params: List<GWord>): SelectOrReportToolT4 {
            return SelectOrReportToolT4(
                feedrate = params.decimalOf('F'),
                s = params.boolOf('S'),
            )
        }
    }
}
