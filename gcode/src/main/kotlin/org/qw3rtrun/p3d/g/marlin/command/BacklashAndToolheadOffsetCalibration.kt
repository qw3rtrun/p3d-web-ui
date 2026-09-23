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
import org.qw3rtrun.p3d.g.code.dsl.word
import org.qw3rtrun.p3d.g.marlin.decimalOf
import org.qw3rtrun.p3d.g.marlin.hasWord
import org.qw3rtrun.p3d.g.marlin.intOf
import org.qw3rtrun.p3d.g.protocol.GRq
import org.qw3rtrun.p3d.g.protocol.GRqDecoder
import java.math.BigDecimal

/**
 * G425 [B] [T<index>] [V] [U<linear>]
 *
 * Backlash and Toolhead Offset Calibration (calibration).
 *
 * @see <a href="https://marlinfw.org/docs/gcode/G425.html">MarlinFirmare G425 doc</a>
 */
data class BacklashAndToolheadOffsetCalibration(
    /** `B` */
    val b: Boolean = false,
    /** `T` - index */
    val index: Int? = null,
    /** `V` */
    val v: Boolean = false,
    /** `U` - linear */
    val linear: BigDecimal? = null,
) : GRq<BacklashAndToolheadOffsetCalibration> {

    override fun encode(): GCommand {
        val words = ArrayList<GWord>(4)
        if (b) words.add(flag('B'))
        if (index != null) words.add(word('T', index))
        if (v) words.add(flag('V'))
        if (linear != null) words.add(word('U', linear.toPlainString()))
        return G(425, *words.toTypedArray())
    }

    override fun toString(): String {
        return javaClass.simpleName + "(" + GEncoder.encode(encode()) + ')'
    }

    companion object : GRqDecoder<BacklashAndToolheadOffsetCalibration> {

        override fun head(): GParameterWord<*> {
            return G(425).head
        }

        override fun decodeParams(tokens: Sequence<GToken>): BacklashAndToolheadOffsetCalibration {
            val params = tokens.toList()
            return BacklashAndToolheadOffsetCalibration(
                b = params.hasWord('B'),
                index = params.intOf('T'),
                v = params.hasWord('V'),
                linear = params.decimalOf('U'),
            )
        }
    }
}
