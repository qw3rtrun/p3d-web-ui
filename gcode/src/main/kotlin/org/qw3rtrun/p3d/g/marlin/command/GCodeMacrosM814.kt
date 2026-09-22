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
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M814
 *
 * G-code macros.
 *
 * **This command also takes a rest-of-line string** (spec 3.4a) which this
 * model cannot hold yet - see todo 09. Only its lettered parameters are here.
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M814.html">MarlinFirmare M814 doc</a>
 */
class GCodeMacrosM814 : GRq<GCodeMacrosM814> {

    override fun encode(): GCommand {
        return M(814)
    }

    override fun equals(other: Any?): Boolean {
        return other is GCodeMacrosM814
    }

    override fun hashCode(): Int {
        return 522366
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<GCodeMacrosM814> {

        override fun head(): GParameterWord<*> {
            return M(814).head
        }

        override fun decodeParams(params: List<GWord>): GCodeMacrosM814 {
            return GCodeMacrosM814()
        }
    }
}
