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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M27 [S<seconds>] [C]
 *
 * Report SD print status (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M27.html">MarlinFirmare M27 doc</a>
 */
data class ReportSDPrintStatus(
    /** `S` - seconds */
    val seconds: Int? = null,
    /** `C` */
    val c: Boolean = false,
) : GRq<ReportSDPrintStatus> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (seconds != null) words.add(word('S', seconds))
        if (c) words.add(flag('C'))
        return M(27, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ReportSDPrintStatus> {

        override fun head(): GParameterWord<*> {
            return M(27).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): ReportSDPrintStatus {
            val params = tokens.toList()
            return ReportSDPrintStatus(
                seconds = params.intOf('S'),
                c = params.hasWord('C'),
            )
        }
    }
}
