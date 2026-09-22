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
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * M404 [W<linear>]
 *
 * Filament Width Sensor Nominal Diameter (control).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/M404.html">MarlinFirmare M404 doc</a>
 */
data class FilamentWidthSensorNominalDiameter(
    /** `W` - linear */
    val linear: BigDecimal? = null,
) : GRq<FilamentWidthSensorNominalDiameter> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(1)
        if (linear != null) words.add(word('W', linear.toPlainString()))
        return M(404, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<FilamentWidthSensorNominalDiameter> {

        override fun head(): GParameterWord<*> {
            return M(404).head
        }

        override fun decodeParams(params: List<GWord>): FilamentWidthSensorNominalDiameter {
            return FilamentWidthSensorNominalDiameter(
                linear = params.decimalOf('W'),
            )
        }
    }
}
