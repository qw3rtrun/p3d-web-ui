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
 * M29
 *
 * Stop SD write (sdcard).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M29.html">MarlinFirmare M29 doc</a>
 */
class StopSDWrite : GRq<StopSDWrite> {

    override fun encode(): GCommand {
        return M(29)
    }

    override fun equals(other: Any?): Boolean {
        return other is StopSDWrite
    }

    override fun hashCode(): Int {
        return 449368
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<StopSDWrite> {

        override fun head(): GParameterWord<*> {
            return M(29).head
        }

        override fun decodeParams(params: List<GWord>): StopSDWrite {
            return StopSDWrite()
        }
    }
}
