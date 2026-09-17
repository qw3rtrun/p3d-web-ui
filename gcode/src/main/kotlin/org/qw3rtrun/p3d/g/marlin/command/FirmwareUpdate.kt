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
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * M997
 *
 * Firmware update (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M997.html">MarlinFirmare M997 doc</a>
 */
class FirmwareUpdate : GRq<FirmwareUpdate> {

    override fun encode(): GCommand {
        return M(997)
    }

    override fun equals(other: Any?): Boolean {
        return other is FirmwareUpdate
    }

    override fun hashCode(): Int {
        return 830502
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<FirmwareUpdate> {

        override fun head(): GParameterWord<*> {
            return M(997).head
        }

        override fun decodeParams(params: List<GWord>): FirmwareUpdate {
            return FirmwareUpdate()
        }
    }
}
