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
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M102 [S<value>]
 *
 * Configure Bed Distance Sensor (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M102.html">MarlinFirmare M102 doc</a>
 */
data class ConfigureBedDistanceSensor(
    /** `S` */
    val s: BigDecimal? = null,
) : GRq<ConfigureBedDistanceSensor> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (s != null) words.add(word('S', s.toPlainString()))
        return M(102, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<ConfigureBedDistanceSensor> {

        override fun head(): GParameterWord<*> {
            return M(102).head
        }

        override fun decodeParams(tokens: List<GToken>): ConfigureBedDistanceSensor {
            return ConfigureBedDistanceSensor(
                s = tokens.decimalOf('S'),
            )
        }
    }
}
