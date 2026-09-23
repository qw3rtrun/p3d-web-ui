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
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * G55
 *
 * Select Workspace (geometry).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G55.html">MarlinFirmare G55 doc</a>
 */
class SelectWorkspaceG55 : GRq<SelectWorkspaceG55> {

    override fun encode(): GCommand {
        return G(55)
    }

    override fun equals(other: Any?): Boolean {
        return other is SelectWorkspaceG55
    }

    override fun hashCode(): Int {
        return 353576
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<SelectWorkspaceG55> {

        override fun head(): GParameterWord<*> {
            return G(55).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): SelectWorkspaceG55 {
            return SelectWorkspaceG55()
        }
    }
}
