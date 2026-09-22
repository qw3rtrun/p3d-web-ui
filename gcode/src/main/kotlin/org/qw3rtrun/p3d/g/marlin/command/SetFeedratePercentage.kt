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
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M220 [S<percent>] [B] [R]
 *
 * Set Feedrate Percentage (motion).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M220.html">MarlinFirmare M220 doc</a>
 */
data class SetFeedratePercentage(
    /** `S` - percent */
    val percent: Int? = null,
    /** `B` */
    val b: Boolean = false,
    /** `R` */
    val r: Boolean = false,
) : GRq<SetFeedratePercentage> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(3)
        if (percent != null) words.add(word('S', percent))
        if (b) words.add(flag('B'))
        if (r) words.add(flag('R'))
        return M(220, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SetFeedratePercentage> {

        override fun head(): GParameterWord<*> {
            return M(220).head
        }

        override fun decodeParams(params: List<GWord>): SetFeedratePercentage {
            return SetFeedratePercentage(
                percent = params.intOf('S'),
                b = params.hasWord('B'),
                r = params.hasWord('R'),
            )
        }
    }
}
