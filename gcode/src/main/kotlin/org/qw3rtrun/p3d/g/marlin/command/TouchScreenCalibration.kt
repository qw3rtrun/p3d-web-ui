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
 * M995
 *
 * Touch Screen Calibration (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M995.html">MarlinFirmare M995 doc</a>
 */
class TouchScreenCalibration : GRq<TouchScreenCalibration> {

    override fun encode(): GCommand {
        return M(995)
    }

    override fun equals(other: Any?): Boolean {
        return other is TouchScreenCalibration
    }

    override fun hashCode(): Int {
        return 2877
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<TouchScreenCalibration> {

        override fun head(): GParameterWord<*> {
            return M(995).head
        }

        override fun decodeParams(params: List<GWord>): TouchScreenCalibration {
            return TouchScreenCalibration()
        }
    }
}
