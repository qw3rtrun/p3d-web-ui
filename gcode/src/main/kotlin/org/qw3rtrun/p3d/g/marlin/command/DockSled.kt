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
 * G31
 *
 * Dock Sled (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G31.html">MarlinFirmare G31 doc</a>
 */
class DockSled : GRq<DockSled> {

    override fun encode(): GCommand {
        return G(31)
    }

    override fun equals(other: Any?): Boolean {
        return other is DockSled
    }

    override fun hashCode(): Int {
        return 217521
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<DockSled> {

        override fun head(): GParameterWord<*> {
            return G(31).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): DockSled {
            return DockSled()
        }
    }
}
