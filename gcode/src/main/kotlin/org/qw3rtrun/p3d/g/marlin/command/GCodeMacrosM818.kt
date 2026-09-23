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
import org.qw3rtrun.p3d.g.code.dsl.bareString
import org.qw3rtrun.p3d.g.marlin.stringArg
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M818 [<gcode>]
 *
 * G-code macros.
 *
 * **`gcode` is a bare rest-of-line string** (spec 3.4a).
 * It carries no letter, it is written last because everything to the end of the
 * line belongs to it, and it cannot contain `;` - every parser reads that as the
 * start of a comment.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M818.html">MarlinFirmare M818 doc</a>
 */
data class GCodeMacrosM818(
    /** the rest of the line */
    val gcode: String? = null,
) : GRq<GCodeMacrosM818> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (gcode != null) words.add(bareString(gcode))
        return M(818, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<GCodeMacrosM818> {

        override fun head(): GParameterWord<*> {
            return M(818).head
        }

        override fun decodeParams(tokens: List<GToken>): GCodeMacrosM818 {
            return GCodeMacrosM818(
                gcode = tokens.stringArg(),
            )
        }
    }
}
