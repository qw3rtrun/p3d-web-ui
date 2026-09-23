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
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M81
 *
 * Power Off (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M81.html">MarlinFirmare M81 doc</a>
 */
class PowerOff : GRq<PowerOff> {

    override fun encode(): GCommand {
        return M(81)
    }

    override fun equals(other: Any?): Boolean {
        return other is PowerOff
    }

    override fun hashCode(): Int {
        return 322433
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<PowerOff> {

        override fun head(): GParameterWord<*> {
            return M(81).head
        }

        override fun decodeParams(tokens: List<GToken>): PowerOff {
            return PowerOff()
        }
    }
}
