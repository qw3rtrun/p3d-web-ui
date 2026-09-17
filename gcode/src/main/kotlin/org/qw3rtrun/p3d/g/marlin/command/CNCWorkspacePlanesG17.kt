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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * G17
 *
 * CNC Workspace Planes (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G17.html">MarlinFirmare G17 doc</a>
 */
class CNCWorkspacePlanesG17 : GRq<CNCWorkspacePlanesG17> {

    override fun encode(): GCommand {
        return G(17)
    }

    override fun equals(other: Any?): Boolean {
        return other is CNCWorkspacePlanesG17
    }

    override fun hashCode(): Int {
        return 700919
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<CNCWorkspacePlanesG17> {

        override fun head(): GParameterWord<*> {
            return G(17).head
        }

        override fun decodeParams(params: List<GWord>): CNCWorkspacePlanesG17 {
            return CNCWorkspacePlanesG17()
        }
    }
}
