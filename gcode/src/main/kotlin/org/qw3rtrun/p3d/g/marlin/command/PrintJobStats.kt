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
 * M78
 *
 * Print Job Stats (printjob).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M78.html">MarlinFirmare M78 doc</a>
 */
class PrintJobStats : GRq<PrintJobStats> {

    override fun encode(): GCommand {
        return M(78)
    }

    override fun equals(other: Any?): Boolean {
        return other is PrintJobStats
    }

    override fun hashCode(): Int {
        return 636404
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<PrintJobStats> {

        override fun head(): GParameterWord<*> {
            return M(78).head
        }

        override fun decodeParams(params: List<GWord>): PrintJobStats {
            return PrintJobStats()
        }
    }
}
