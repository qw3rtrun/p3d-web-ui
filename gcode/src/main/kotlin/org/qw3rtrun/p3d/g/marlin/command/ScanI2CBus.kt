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
 * M265
 *
 * Scan I2C Bus (i2c).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M265.html">MarlinFirmare M265 doc</a>
 */
class ScanI2CBus : GRq<ScanI2CBus> {

    override fun encode(): GCommand {
        return M(265)
    }

    override fun equals(other: Any?): Boolean {
        return other is ScanI2CBus
    }

    override fun hashCode(): Int {
        return 27275
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ScanI2CBus> {

        override fun head(): GParameterWord<*> {
            return M(265).head
        }

        override fun decodeParams(tokens: List<GToken>): ScanI2CBus {
            return ScanI2CBus()
        }
    }
}
