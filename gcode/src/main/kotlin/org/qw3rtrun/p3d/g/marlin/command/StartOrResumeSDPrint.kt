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
import org.qw3rtrun.p3d.g.marlin.longOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M24 [S<pos>] [T<time>]
 *
 * Start or Resume SD print (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M24.html">MarlinFirmare M24 doc</a>
 */
data class StartOrResumeSDPrint(
    /** `S` - pos */
    val pos: Long? = null,
    /** `T` - time */
    val time: Long? = null,
) : GRq<StartOrResumeSDPrint> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (pos != null) words.add(word('S', BigDecimal.valueOf(pos)))
        if (time != null) words.add(word('T', BigDecimal.valueOf(time)))
        return M(24, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<StartOrResumeSDPrint> {

        override fun head(): GParameterWord<*> {
            return M(24).head
        }

        override fun decodeParams(params: List<GWord>): StartOrResumeSDPrint {
            return StartOrResumeSDPrint(
                pos = params.longOf('S'),
                time = params.longOf('T'),
            )
        }
    }
}
