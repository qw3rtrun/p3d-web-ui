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
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.G
import org.qw3rtrun.p3d.g.code.dsl.flag
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder

/**
 * G76 [B] [P]
 *
 * Probe temperature calibration (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G76.html">MarlinFirmare G76 doc</a>
 */
data class ProbeTemperatureCalibration(
    /** `B` */
    val b: Boolean = false,
    /** `P` */
    val p: Boolean = false,
) : GRq<ProbeTemperatureCalibration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(2)
        if (b) words.add(flag('B'))
        if (p) words.add(flag('P'))
        return G(76, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ProbeTemperatureCalibration> {

        override fun head(): GParameterWord<*> {
            return G(76).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): ProbeTemperatureCalibration {
            val params = tokens.toList()
            return ProbeTemperatureCalibration(
                b = params.hasWord('B'),
                p = params.hasWord('P'),
            )
        }
    }
}
