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
 * M26 [S<pos>]
 *
 * Set SD position (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M26.html">MarlinFirmare M26 doc</a>
 */
data class SetSDPosition(
    /** `S` - pos */
    val pos: Long? = null,
) : GRq<SetSDPosition> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (pos != null) words.add(word('S', BigDecimal.valueOf(pos)))
        return M(26, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetSDPosition> {

        override fun head(): GParameterWord<*> {
            return M(26).head
        }

        override fun decodeParams(params: List<GWord>): SetSDPosition {
            return SetSDPosition(
                pos = params.longOf('S'),
            )
        }
    }
}
