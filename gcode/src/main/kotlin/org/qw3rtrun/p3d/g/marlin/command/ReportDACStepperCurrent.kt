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
 * M909
 *
 * Report DAC Stepper Current (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M909.html">MarlinFirmare M909 doc</a>
 */
class ReportDACStepperCurrent : GRq<ReportDACStepperCurrent> {

    override fun encode(): GCommand {
        return M(909)
    }

    override fun equals(other: Any?): Boolean {
        return other is ReportDACStepperCurrent
    }

    override fun hashCode(): Int {
        return 323129
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ReportDACStepperCurrent> {

        override fun head(): GParameterWord<*> {
            return M(909).head
        }

        override fun decodeParams(tokens: List<GToken>): ReportDACStepperCurrent {
            return ReportDACStepperCurrent()
        }
    }
}
